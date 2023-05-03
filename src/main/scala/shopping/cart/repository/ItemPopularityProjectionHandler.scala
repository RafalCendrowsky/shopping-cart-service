package shopping.cart.repository

import akka.actor.typed.ActorSystem
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import org.slf4j.LoggerFactory
import shopping.cart.ShoppingCart.{CheckedOut, Event, ItemAdded, ItemQuantityAdjusted, ItemRemoved}

class ItemPopularityProjectionHandler(
  tag: String,
  system: ActorSystem[_],
  repository: ItemPopularityRepository,
) extends JdbcHandler[
  EventEnvelope[Event],
  ScalikeJdbcSession]() {

  private val log = LoggerFactory.getLogger(getClass)

  override def process(
    session: ScalikeJdbcSession,
    envelope: EventEnvelope[Event]
  ): Unit = {
    envelope.event match {
      case ItemAdded(itemId, _, quantity) =>
        repository.update(session, itemId, +quantity)
        logItemCount(session, itemId)
      case ItemRemoved(itemId, _, quantity) =>
        repository.update(session, itemId, -quantity)
        logItemCount(session, itemId)
      case ItemQuantityAdjusted(itemId, _, oldQuantity, newQuantity) =>
        repository.update(session, itemId, + newQuantity - oldQuantity)
      case _: CheckedOut  => ()
    }
  }

  private def logItemCount(
    session: ScalikeJdbcSession,
    itemId: String
  ): Unit = {
    log.info(
      "ItemPopularityProjectionHandler({}) item popularity for '{}': {}",
      tag,
      itemId,
      repository.getItem(session, itemId).getOrElse(0)
    )
  }

}
