package shopping.cart

import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}

import java.time.Instant
import scala.concurrent.duration.DurationInt

object ShoppingCart {

  sealed trait Command extends CborSerializable

  final case class AddItem(
    itemId: String,
    quantity: Int,
    replyTo: ActorRef[StatusReply[Summary]]
  ) extends Command

  final case class RemoveItem(
    itemId: String,
    replyTo: ActorRef[StatusReply[Summary]]
  ) extends Command

  final case class AdjustItemQuantity(
    itemId: String,
    quantity: Int,
    replyTo: ActorRef[StatusReply[Summary]]
  ) extends Command

  final case class Checkout(replyTo: ActorRef[StatusReply[Summary]]) extends Command

  final case class Get(replyTo: ActorRef[Summary]) extends Command

  final case class Summary(items: Map[String, Int], isCheckedOut: Boolean) extends CborSerializable

  sealed trait Event extends CborSerializable {
    def cartId: String
  }

  final case class ItemAdded(
    cartId: String,
    itemId: String,
    quantity: Int
  ) extends Event

  final case class ItemRemoved(
    cartId: String,
    itemId: String,
    quantity: Int
  ) extends Event

  final case class ItemQuantityAdjusted(
    cartId: String,
    itemId: String,
    oldQuantity: Int,
    newQuantity: Int
  ) extends Event

  final case class CheckedOut(cartId: String, eventTime: Instant) extends Event

  final case class State(items: Map[String, Int], checkoutDate: Option[Instant]) extends CborSerializable {
    def isCheckedOut: Boolean = checkoutDate.isDefined

    def hasItem(itemId: String): Boolean = items.contains(itemId)

    def isEmpty: Boolean = items.isEmpty

    def updateItem(itemId: String, quantity: Int): State =
      quantity match {
        case 0 => copy(items = items - itemId)
        case _ => copy(items = items + (itemId -> quantity))
      }

    def toSummary: Summary = Summary(items, isCheckedOut)
  }

  object State {
    val empty: State = State(items = Map.empty, checkoutDate = None)
  }

  val EntityKey: EntityTypeKey[Command] = EntityTypeKey[Command]("ShoppingCart")

  val tags: Seq[String] = Vector.tabulate(5)(i => s"carts-$i")

  def init(system: ActorSystem[_]): Unit = {
    val behaviorFactory: EntityContext[Command] => Behavior[Command] = {
      entityContext =>
        val i = math.abs(entityContext.entityId.hashCode % tags.size)
        val selectedTag = tags(i)
        ShoppingCart(entityContext.entityId, selectedTag)
    }
    ClusterSharding(system).init(Entity(EntityKey)(behaviorFactory))
  }

  def apply(cartId: String, projectionTag: String): Behavior[Command] = {
    EventSourcedBehavior.withEnforcedReplies[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(cartId),
      emptyState = State.empty,
      commandHandler = (state, command) => onCommand(cartId, state, command),
      eventHandler = (state, event) => applyEvent(state, event))
      .withTagger(_ => Set(projectionTag))
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
    if (state.isCheckedOut) {
      checkedOutShoppingCart(state, command)
    } else {
      openShoppingCart(cartId, state, command)
    }
  }

  private def openShoppingCart(
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
            .persist(ItemAdded(cartId, itemId, quantity))
            .thenReply(replyTo) { updatedCart =>
              StatusReply.Success(Summary(updatedCart.items, updatedCart.isCheckedOut))
            }
        }

      case RemoveItem(itemId, replyTo) =>
        if (state.hasItem(itemId)) {
          Effect
            .persist(ItemRemoved(cartId, itemId, state.items(itemId)))
            .thenReply(replyTo) { updatedCart =>
              StatusReply.Success(Summary(updatedCart.items, updatedCart.isCheckedOut))
            }
        } else {
          Effect.reply(replyTo)(
            StatusReply.Error("Item not found")
          )
        }

      case AdjustItemQuantity(itemId, quantity, replyTo) =>
        if (quantity <= 0) {
          Effect.reply(replyTo){
            StatusReply.Error("Quantity must be greater than zero")
          }
        } else if (state.hasItem(itemId)) {
          Effect
            .persist(
              ItemQuantityAdjusted(cartId, itemId, state.items.getOrElse(itemId, 0), quantity))
            .thenReply(replyTo) { updatedCart =>
              StatusReply.Success(Summary(updatedCart.items, updatedCart.isCheckedOut))
            }
        } else {
          Effect.reply(replyTo)(
            StatusReply.Error("Item not found")
          )
        }

      case Checkout(replyTo) =>
        if (state.isEmpty) {
          Effect.reply(replyTo)(
            StatusReply.Error("Can't checkout an empty shopping cart")
          )
        } else {
          Effect
            .persist(CheckedOut(cartId, Instant.now()))
            .thenReply(replyTo) { updatedCart =>
              StatusReply.Success(Summary(updatedCart.items, updatedCart.isCheckedOut))
            }
        }

      case Get(replyTo) =>
        Effect.reply(replyTo)(state.toSummary)
    }
  }

  private def checkedOutShoppingCart(state: State, command: Command): ReplyEffect[Event, State] = {
    command match {
      case AddItem(_, _, replyTo) =>
          Effect.reply(replyTo)(
            StatusReply.Error("Can't add an item to a checked out shopping cart"))
      case RemoveItem(_, replyTo) =>
          Effect.reply(replyTo)(
            StatusReply.Error("Can't remove an item from a checked out shopping cart"))
      case AdjustItemQuantity(_, _, replyTo) =>
          Effect.reply(replyTo)(
            StatusReply.Error("Can't adjust item quantity in a checked out shopping cart"))
      case Checkout(replyTo) =>
          Effect.reply(replyTo)(
            StatusReply.Error("Can't checkout an already checked out shopping cart"))
      case Get(replyTo) =>
        Effect.reply(replyTo)(state.toSummary)
    }
  }

  private def applyEvent(state: State, event: Event): State = {
    event match {
      case ItemAdded(_, itemId, quantity) =>
        state.updateItem(itemId, quantity)

      case CheckedOut(_, eventTime) =>
        state.copy(checkoutDate = Some(eventTime))

      case ItemRemoved(_, itemId, _) =>
        state.copy(items = state.items - itemId)

      case ItemQuantityAdjusted(_, itemId, _, newQuantity) =>
        state.updateItem(itemId, newQuantity)
    }
  }
}
