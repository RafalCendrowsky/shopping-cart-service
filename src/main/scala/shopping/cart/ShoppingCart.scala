package shopping.cart

import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}

import scala.concurrent.duration.DurationInt

object ShoppingCart {

  sealed trait Command extends CborSerializable

  final case class AddItem(
    itemId: String,
    quantity: Int,
    replyTo: ActorRef[StatusReply[Summary]]
  ) extends Command

  final case class Summary(items: Map[String, Int]) extends CborSerializable

  sealed trait Event extends CborSerializable

  final case class ItemAdded(
    itemId: String,
    cartId: String,
    quantity: Int
  ) extends Event

  final case class State(items: Map[String, Int]) extends CborSerializable {
    def hasItem(itemId: String): Boolean = items.contains(itemId)

    def isEmpty: Boolean = items.isEmpty

    def updateItem(itemId: String, quantity: Int): State =
      quantity match {
        case 0 => copy(items = items - itemId)
        case _ => copy(items = {
          val prevQuantity = items.getOrElse(itemId, 0)
          items + (itemId -> (prevQuantity + quantity))
        })
      }
  }

  object State {
    val empty: State = State(items = Map.empty)
  }

  val EntityKey: EntityTypeKey[Command] = EntityTypeKey[Command]("ShoppingCart")

  def init(system: ActorSystem[_]): Unit = {
    ClusterSharding(system).init(Entity(EntityKey) { entityContext =>
      ShoppingCart(entityContext.entityId)
    })
  }

  def apply(cartId: String): Behavior[Command] = {
    EventSourcedBehavior.withEnforcedReplies[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(cartId),
      emptyState = State.empty,
      commandHandler = (state, command) => onCommand(cartId, state, command),
      eventHandler = (state, event) => applyEvent(state, event))
      .withRetention(
        RetentionCriteria
          .snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
      .onPersistFailure(
        SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1)
      )
  }

  private def onCommand(
    cartId: String,
    state: State,
    command: Command
  ): ReplyEffect[Event, State] = {
    command match {
      case AddItem(itemId, quantity, replyTo) =>
        if (state.hasItem(itemId)) {
          Effect.reply(replyTo)(
            StatusReply.Error("Item was already added to this shopping cart")
          )
        }
        else if (quantity <= 0) {
          Effect.reply(replyTo)(
            StatusReply.Error("Quantity must be greater than zero")
          )
        } else {
          Effect
            .persist(ItemAdded(itemId, cartId, quantity))
            .thenReply(replyTo) { updatedCart =>
              StatusReply.Success(Summary(updatedCart.items))
            }
        }
    }
  }

  private def applyEvent(state: State, event: Event): State = {
    event match {
      case ItemAdded(itemId, _, quantity) =>
        state.updateItem(itemId, quantity)
    }
  }
}
