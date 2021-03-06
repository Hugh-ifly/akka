/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.ddata.internal

import scala.compat.java8.OptionConverters._
import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import scala.concurrent.Future

import akka.annotation.InternalApi
import akka.cluster.{ ddata ⇒ dd }
import akka.pattern.ask
import akka.typed.ActorRef
import akka.typed.Behavior
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.util.Timeout
import akka.cluster.ddata.ReplicatedData
import akka.cluster.ddata.Key
import akka.typed.Terminated

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ReplicatorBehavior {
  import akka.typed.cluster.ddata.javadsl.{ Replicator ⇒ JReplicator }
  import akka.typed.cluster.ddata.scaladsl.{ Replicator ⇒ SReplicator }

  private case class InternalChanged[A <: ReplicatedData](chg: dd.Replicator.Changed[A], subscriber: ActorRef[JReplicator.Changed[A]])
    extends JReplicator.Command

  val localAskTimeout = 60.seconds // ReadLocal, WriteLocal shouldn't timeout
  val additionalAskTimeout = 1.second

  def behavior(settings: dd.ReplicatorSettings, underlyingReplicator: Option[akka.actor.ActorRef]): Behavior[SReplicator.Command] = {

    Actor.deferred { ctx ⇒
      val untypedReplicator = underlyingReplicator match {
        case Some(ref) ⇒ ref
        case None ⇒
          // FIXME perhaps add supervisor for restarting
          val untypedReplicatorProps = dd.Replicator.props(settings)
          ctx.actorOf(untypedReplicatorProps, name = "underlying")
      }

      def withState(
        subscribeAdapters: Map[ActorRef[JReplicator.Changed[ReplicatedData]], ActorRef[dd.Replicator.Changed[ReplicatedData]]]): Behavior[SReplicator.Command] = {

        def stopSubscribeAdapter(subscriber: ActorRef[JReplicator.Changed[ReplicatedData]]): Behavior[SReplicator.Command] = {
          subscribeAdapters.get(subscriber) match {
            case Some(adapter) ⇒
              // will be unsubscribed from untypedReplicator via Terminated
              ctx.stop(adapter)
              withState(subscribeAdapters - subscriber)
            case None ⇒ // already unsubscribed or terminated
              Actor.same
          }
        }

        Actor.immutable[SReplicator.Command] { (ctx, msg) ⇒
          msg match {
            case cmd: SReplicator.Get[_] ⇒
              untypedReplicator.tell(
                dd.Replicator.Get(cmd.key, cmd.consistency, cmd.request),
                sender = cmd.replyTo.toUntyped)
              Actor.same

            case cmd: JReplicator.Get[d] ⇒
              implicit val timeout = Timeout(cmd.consistency.timeout match {
                case Duration.Zero ⇒ localAskTimeout
                case t             ⇒ t + additionalAskTimeout
              })
              import ctx.executionContext
              val reply =
                (untypedReplicator ? dd.Replicator.Get(cmd.key, cmd.consistency.toUntyped, cmd.request.asScala))
                  .mapTo[dd.Replicator.GetResponse[d]].map {
                    case rsp: dd.Replicator.GetSuccess[d] ⇒ JReplicator.GetSuccess(rsp.key, rsp.request.asJava)(rsp.dataValue)
                    case rsp: dd.Replicator.NotFound[d]   ⇒ JReplicator.NotFound(rsp.key, rsp.request.asJava)
                    case rsp: dd.Replicator.GetFailure[d] ⇒ JReplicator.GetFailure(rsp.key, rsp.request.asJava)
                  }.recover {
                    case _ ⇒ JReplicator.GetFailure(cmd.key, cmd.request)
                  }
              reply.foreach { cmd.replyTo ! _ }
              Actor.same

            case cmd: SReplicator.Update[_] ⇒
              untypedReplicator.tell(
                dd.Replicator.Update(cmd.key, cmd.writeConsistency, cmd.request)(cmd.modify),
                sender = cmd.replyTo.toUntyped)
              Actor.same

            case cmd: JReplicator.Update[d] ⇒
              implicit val timeout = Timeout(cmd.writeConsistency.timeout match {
                case Duration.Zero ⇒ localAskTimeout
                case t             ⇒ t + additionalAskTimeout
              })
              import ctx.executionContext
              val reply =
                (untypedReplicator ? dd.Replicator.Update(cmd.key, cmd.writeConsistency.toUntyped, cmd.request.asScala)(cmd.modify))
                  .mapTo[dd.Replicator.UpdateResponse[d]].map {
                    case rsp: dd.Replicator.UpdateSuccess[d] ⇒ JReplicator.UpdateSuccess(rsp.key, rsp.request.asJava)
                    case rsp: dd.Replicator.UpdateTimeout[d] ⇒ JReplicator.UpdateTimeout(rsp.key, rsp.request.asJava)
                    case rsp: dd.Replicator.ModifyFailure[d] ⇒ JReplicator.ModifyFailure(rsp.key, rsp.errorMessage, rsp.cause, rsp.request.asJava)
                    case rsp: dd.Replicator.StoreFailure[d]  ⇒ JReplicator.StoreFailure(rsp.key, rsp.request.asJava)
                  }.recover {
                    case _ ⇒ JReplicator.UpdateTimeout(cmd.key, cmd.request)
                  }
              reply.foreach { cmd.replyTo ! _ }
              Actor.same

            case cmd: SReplicator.Subscribe[_] ⇒
              // For the Scala API the Changed messages can be sent directly to the subscriber
              untypedReplicator.tell(
                dd.Replicator.Subscribe(cmd.key, cmd.subscriber.toUntyped),
                sender = cmd.subscriber.toUntyped)
              Actor.same

            case cmd: JReplicator.Subscribe[ReplicatedData] @unchecked ⇒
              // For the Java API the Changed messages must be mapped to the JReplicator.Changed class.
              // That is done with an adapter, and we have to keep track of the lifecycle of the original
              // subscriber and stop the adapter when the original subscriber is stopped.
              val adapter: ActorRef[dd.Replicator.Changed[ReplicatedData]] = ctx.spawnAdapter {
                chg ⇒ InternalChanged(chg, cmd.subscriber)
              }

              untypedReplicator.tell(
                dd.Replicator.Subscribe(cmd.key, adapter.toUntyped),
                sender = akka.actor.ActorRef.noSender)

              ctx.watch(cmd.subscriber)

              withState(subscribeAdapters.updated(cmd.subscriber, adapter))

            case InternalChanged(chg, subscriber) ⇒
              subscriber ! JReplicator.Changed(chg.key)(chg.dataValue)
              Actor.same

            case cmd: JReplicator.Unsubscribe[ReplicatedData] @unchecked ⇒
              stopSubscribeAdapter(cmd.subscriber)

            case cmd: SReplicator.Delete[_] ⇒
              untypedReplicator.tell(
                dd.Replicator.Delete(cmd.key, cmd.consistency, cmd.request),
                sender = cmd.replyTo.toUntyped)
              Actor.same

            case cmd: JReplicator.Delete[d] ⇒
              implicit val timeout = Timeout(cmd.consistency.timeout match {
                case Duration.Zero ⇒ localAskTimeout
                case t             ⇒ t + additionalAskTimeout
              })
              import ctx.executionContext
              val reply =
                (untypedReplicator ? dd.Replicator.Delete(cmd.key, cmd.consistency.toUntyped, cmd.request.asScala))
                  .mapTo[dd.Replicator.DeleteResponse[d]].map {
                    case rsp: dd.Replicator.DeleteSuccess[d]            ⇒ JReplicator.DeleteSuccess(rsp.key, rsp.request.asJava)
                    case rsp: dd.Replicator.ReplicationDeleteFailure[d] ⇒ JReplicator.ReplicationDeleteFailure(rsp.key, rsp.request.asJava)
                    case rsp: dd.Replicator.DataDeleted[d]              ⇒ JReplicator.DataDeleted(rsp.key, rsp.request.asJava)
                    case rsp: dd.Replicator.StoreFailure[d]             ⇒ JReplicator.StoreFailure(rsp.key, rsp.request.asJava)
                  }.recover {
                    case _ ⇒ JReplicator.ReplicationDeleteFailure(cmd.key, cmd.request)
                  }
              reply.foreach { cmd.replyTo ! _ }
              Actor.same

            case SReplicator.GetReplicaCount(replyTo) ⇒
              untypedReplicator.tell(dd.Replicator.GetReplicaCount, sender = replyTo.toUntyped)
              Actor.same

            case JReplicator.GetReplicaCount(replyTo) ⇒
              implicit val timeout = Timeout(localAskTimeout)
              import ctx.executionContext
              val reply =
                (untypedReplicator ? dd.Replicator.GetReplicaCount)
                  .mapTo[dd.Replicator.ReplicaCount].map(rsp ⇒ JReplicator.ReplicaCount(rsp.n))
              reply.foreach { replyTo ! _ }
              Actor.same

            case SReplicator.FlushChanges | JReplicator.FlushChanges ⇒
              untypedReplicator.tell(dd.Replicator.FlushChanges, sender = akka.actor.ActorRef.noSender)
              Actor.same

          }
        }
          .onSignal {
            case (ctx, Terminated(ref: ActorRef[JReplicator.Changed[ReplicatedData]] @unchecked)) ⇒
              stopSubscribeAdapter(ref)
          }
      }

      withState(Map.empty)

    }
  }
}
