package shopping.cart

import akka.Done
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.Handler
import akka.util.Timeout
import org.slf4j.LoggerFactory
import shopping.cart.ShoppingCart.{CheckedOut, Checkout, Event, Get}
import shopping.order.proto.{OrderRequest, ShoppingOrderService}

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}

class SendOrderProjectionHandler(
  system: ActorSystem[_],
  orderService: ShoppingOrderService,
) extends Handler[EventEnvelope[Event]]{
  private val log = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext = system.executionContext

  private val sharding = ClusterSharding(system)

  override def process(envelope: EventEnvelope[Event]): Future[Done] = {
    envelope.event match {
      case checkout: CheckedOut =>
        sendOrder(checkout)
      case _ => Future.successful(Done)
    }
  }

  private def sendOrder(checkout: CheckedOut): Future[Done] = {
    val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, checkout.cartId)
    entityRef.ask(Get)(Timeout.apply(3, TimeUnit.SECONDS)).flatMap { cart =>
      val items = cart.items.iterator.map { case (itemId, quantity) =>
        shopping.order.proto.Item(itemId, quantity)
      }.toList
      log.info(
        "Sending order of {} items for cart {}",
        items.size,
        checkout.cartId
      )
      val orderReq = OrderRequest(checkout.cartId, items)
      orderService.order(orderReq).map(_ => Done)
    }
  }
}
