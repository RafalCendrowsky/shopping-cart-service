package shopping.cart

import akka.Done
import akka.actor.typed.ActorSystem
import akka.kafka.scaladsl.SendProducer
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.Handler
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import shopping.cart.ShoppingCart.Event
import com.google.protobuf.any.{ Any => ScalaPBAny }

import scala.concurrent.{ExecutionContext, Future}

class PublishEventsProjectionHandler(
  system: ActorSystem[_],
  topic: String,
  sendProducer: SendProducer[String, Array[Byte]]
) extends  Handler[EventEnvelope[Event]] {
  private val log = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext = system.executionContext

  override def process(envelope: EventEnvelope[Event]): Future[Done] = {
    val event = envelope.event
    val key = event.cartId
    val producerRecord = new ProducerRecord(topic, key, serialize(event))
    val result = sendProducer.send(producerRecord).map { recordMetadata =>
      log.info(
        "Published event [{}] to topic/partition {}/{}",
        event,
        topic,
        recordMetadata.partition())
      Done
      }
    result
  }

  private def serialize(event: Event): Array[Byte] = {
    val protoMessage = event match {
      case ShoppingCart.ItemAdded(cartId, itemId, quantity) =>
        proto.ItemAdded(cartId, itemId, quantity)
      case ShoppingCart.ItemRemoved(cartId, itemId, _) =>
        proto.ItemRemoved(cartId, itemId)
      case ShoppingCart.ItemQuantityAdjusted(cartId, itemId, _, newQuantity) =>
        proto.ItemQuantityAdjusted(cartId, itemId, newQuantity)
      case ShoppingCart.CheckedOut(cartId, _) =>
        proto.CheckedOut(cartId)
    }
    ScalaPBAny.pack(protoMessage, "shopping-cart-service").toByteArray
  }
}
