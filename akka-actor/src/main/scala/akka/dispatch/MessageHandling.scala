/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch

import java.util.concurrent._
import akka.event.EventHandler
import akka.config.Configuration
import akka.config.Config.TIME_UNIT
import akka.util.{Duration, Switch, ReentrantGuard}
import java.util.concurrent.ThreadPoolExecutor.{AbortPolicy, CallerRunsPolicy, DiscardOldestPolicy, DiscardPolicy}
import akka.actor._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final case class MessageInvocation(val receiver: ActorRef,
                                   val message: Any,
                                   val sender: Option[ActorRef],
                                   val senderFuture: Option[CompletableFuture[Any]]) {
  if (receiver eq null) throw new IllegalArgumentException("Receiver can't be null")

  def invoke = try {
    receiver.invoke(this)
  } catch {
    case e: NullPointerException => throw new ActorInitializationException(
      "Don't call 'self ! message' in the Actor's constructor (in Scala this means in the body of the class).")
  }
}

final case class FutureInvocation(future: CompletableFuture[Any], function: () => Any) extends Runnable {
  val uuid = akka.actor.newUuid

  def run = future complete (try {
    Right(function.apply)
  } catch {
    case e =>
      EventHandler.error(e, this, e.getMessage)
      Left(e)
  })
}

object MessageDispatcher {
  val UNSCHEDULED = 0
  val SCHEDULED   = 1
  val RESCHEDULED = 2

  implicit def defaultGlobalDispatcher = Dispatchers.defaultGlobalDispatcher
}

/**
 *  @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MessageDispatcher {
  import MessageDispatcher._

  protected val uuids   = new ConcurrentSkipListSet[Uuid]
  protected val futures = new ConcurrentSkipListSet[Uuid]
  protected val guard   = new ReentrantGuard
  protected val active  = new Switch(false)

  private var shutdownSchedule = UNSCHEDULED //This can be non-volatile since it is protected by guard withGuard

  /**
   *  Creates and returns a mailbox for the given actor.
   */
  private[akka] def createMailbox(actorRef: ActorRef): AnyRef

  /**
   * Attaches the specified actorRef to this dispatcher
   */
  final def attach(actorRef: ActorRef): Unit = guard withGuard {
    register(actorRef)
  }

  /**
   * Detaches the specified actorRef from this dispatcher
   */
  final def detach(actorRef: ActorRef): Unit = guard withGuard {
    unregister(actorRef)
  }

  private[akka] final def dispatchMessage(invocation: MessageInvocation): Unit = dispatch(invocation)

  private[akka] final def dispatchFuture(invocation: FutureInvocation): Unit = {
    guard withGuard {
      futures add invocation.uuid
      if (active.isOff) { active.switchOn { start } }
    }
    invocation.future.onComplete { f =>
      guard withGuard {
        futures remove invocation.uuid
        if (futures.isEmpty && uuids.isEmpty) {
          shutdownSchedule match {
            case UNSCHEDULED =>
              shutdownSchedule = SCHEDULED
              Scheduler.scheduleOnce(shutdownAction, timeoutMs, TimeUnit.MILLISECONDS)
            case SCHEDULED =>
              shutdownSchedule = RESCHEDULED
            case RESCHEDULED => //Already marked for reschedule
          }
        }
      }
    }
    executeFuture(invocation)
  }

  private[akka] def register(actorRef: ActorRef) {
    if (actorRef.mailbox eq null)
      actorRef.mailbox = createMailbox(actorRef)

    uuids add actorRef.uuid
    if (active.isOff) {
      active.switchOn {
        start
      }
    }
  }

  private[akka] def unregister(actorRef: ActorRef) = {
    if (uuids remove actorRef.uuid) {
      actorRef.mailbox = null
      if (uuids.isEmpty && futures.isEmpty){
        shutdownSchedule match {
          case UNSCHEDULED =>
            shutdownSchedule = SCHEDULED
            Scheduler.scheduleOnce(shutdownAction, timeoutMs, TimeUnit.MILLISECONDS)
          case SCHEDULED =>
            shutdownSchedule = RESCHEDULED
          case RESCHEDULED => //Already marked for reschedule
        }
      }
    }
  }

  /**
   * Traverses the list of actors (uuids) currently being attached to this dispatcher and stops those actors
   */
  def stopAllAttachedActors {
    val i = uuids.iterator
    while (i.hasNext()) {
      val uuid = i.next()
      Actor.registry.actorFor(uuid) match {
        case Some(actor) => actor.stop()
        case None        => {}
      }
    }
  }

  private val shutdownAction = new Runnable {
    def run = guard withGuard {
      shutdownSchedule match {
        case RESCHEDULED =>
          shutdownSchedule = SCHEDULED
          Scheduler.scheduleOnce(this, timeoutMs, TimeUnit.MILLISECONDS)
        case SCHEDULED =>
          if (uuids.isEmpty() && futures.isEmpty) {
            active switchOff {
              shutdown // shut down in the dispatcher's references is zero
            }
          }
          shutdownSchedule = UNSCHEDULED
        case UNSCHEDULED => //Do nothing
      }
    }
  }

  /**
   * When the dispatcher no longer has any actors registered, how long will it wait until it shuts itself down, in Ms
   * defaulting to your akka configs "akka.actor.dispatcher-shutdown-timeout" or otherwise, 1 Second
   */
  private[akka] def timeoutMs: Long = Dispatchers.DEFAULT_SHUTDOWN_TIMEOUT.toMillis

  /**
   * After the call to this method, the dispatcher mustn't begin any new message processing for the specified reference
   */
  def suspend(actorRef: ActorRef): Unit

  /*
   * After the call to this method, the dispatcher must begin any new message processing for the specified reference
   */
  def resume(actorRef: ActorRef): Unit

  /**
   *   Will be called when the dispatcher is to queue an invocation for execution
   */
  private[akka] def dispatch(invocation: MessageInvocation): Unit

  private[akka] def executeFuture(invocation: FutureInvocation): Unit

  /**
   * Called one time every time an actor is attached to this dispatcher and this dispatcher was previously shutdown
   */
  private[akka] def start: Unit

  /**
   * Called one time every time an actor is detached from this dispatcher and this dispatcher has no actors left attached
   */
  private[akka] def shutdown: Unit

  /**
   * Returns the size of the mailbox for the specified actor
   */
  def mailboxSize(actorRef: ActorRef): Int

  /**
   * Returns the size of the Future queue
   */
  def futureQueueSize: Int = futures.size
}

/**
 * Trait to be used for hooking in new dispatchers into Dispatchers.fromConfig
 */
abstract class MessageDispatcherConfigurator {
  /**
   * Returns an instance of MessageDispatcher given a Configuration
   */
  def configure(config: Configuration): MessageDispatcher

  def mailboxType(config: Configuration): MailboxType = {
    val capacity = config.getInt("mailbox-capacity", Dispatchers.MAILBOX_CAPACITY)
    // FIXME how do we read in isBlocking for mailbox? Now set to 'false'.
    if (capacity < 1) UnboundedMailbox()
    else BoundedMailbox(false, capacity, Duration(config.getInt("mailbox-push-timeout-time", Dispatchers.MAILBOX_PUSH_TIME_OUT.toMillis.toInt), TIME_UNIT))
  }

  def configureThreadPool(config: Configuration, createDispatcher: => (ThreadPoolConfig) => MessageDispatcher): ThreadPoolConfigDispatcherBuilder = {
    import ThreadPoolConfigDispatcherBuilder.conf_?

    //Apply the following options to the config if they are present in the config
    ThreadPoolConfigDispatcherBuilder(createDispatcher,ThreadPoolConfig()).configure(
      conf_?(config getInt    "keep-alive-time"      )(time   => _.setKeepAliveTime(Duration(time, TIME_UNIT))),
      conf_?(config getDouble "core-pool-size-factor")(factor => _.setCorePoolSizeFromFactor(factor)),
      conf_?(config getDouble "max-pool-size-factor" )(factor => _.setMaxPoolSizeFromFactor(factor)),
      conf_?(config getInt    "executor-bounds"      )(bounds => _.setExecutorBounds(bounds)),
      conf_?(config getBool   "allow-core-timeout"   )(allow  => _.setAllowCoreThreadTimeout(allow)),
      conf_?(config getString "rejection-policy" map {
        case "abort"          => new AbortPolicy()
        case "caller-runs"    => new CallerRunsPolicy()
        case "discard-oldest" => new DiscardOldestPolicy()
        case "discard"        => new DiscardPolicy()
        case x                => throw new IllegalArgumentException("[%s] is not a valid rejectionPolicy!" format x)
      })(policy => _.setRejectionPolicy(policy)))
  }
}
