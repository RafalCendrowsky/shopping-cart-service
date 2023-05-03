package shopping.cart

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.pattern.StatusReply
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike
import shopping.cart.ShoppingCart.{Command, Event, State}

object ShoppingCartSpec {
  val config: Config = ConfigFactory.parseString(
    """
    akka.actor.serialization-bindings {
      "shopping.cart.CborSerializable" = jackson-cbor
    }
    """)
    .withFallback(EventSourcedBehaviorTestKit.config)
}

class ShoppingCartSpec
  extends ScalaTestWithActorTestKit(ShoppingCartSpec.config)
    with AnyWordSpecLike
    with BeforeAndAfterEach {

  private val cartId = "testCart"
  private val eventSourcedTestKit = EventSourcedBehaviorTestKit[Command, Event, State](
    system,
    ShoppingCart(cartId)
  )

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  "The Shopping Cart" must {
    "be able to add an item" in {
      val result = eventSourcedTestKit.runCommand(replyTo => ShoppingCart.AddItem("foo", 42, replyTo))
      result.reply shouldBe StatusReply.Success(ShoppingCart.Summary(Map("foo" -> 42)))
      result.event shouldBe ShoppingCart.ItemAdded("foo", cartId, 42)
      result.state shouldBe ShoppingCart.State(Map("foo" -> 42))
    }

    "be able to add multiple items" in {
      val result1 = eventSourcedTestKit.runCommand(replyTo => ShoppingCart.AddItem("foo", 42, replyTo))
      val result2 = eventSourcedTestKit.runCommand(replyTo => ShoppingCart.AddItem("bar", 17, replyTo))

      result2.reply shouldBe StatusReply.Success(ShoppingCart.Summary(Map("foo" -> 42, "bar" -> 17)))
      result2.event shouldBe ShoppingCart.ItemAdded("bar", cartId, 17)
      result2.state shouldBe ShoppingCart.State(Map("foo" -> 42, "bar" -> 17))
    }

    "reject adding an existing item" in {
      val result1 = eventSourcedTestKit.runCommand(replyTo => ShoppingCart.AddItem("foo", 42, replyTo))
      val result2 = eventSourcedTestKit.runCommand(replyTo => ShoppingCart.AddItem("foo", 1, replyTo))

      result2.reply shouldBe StatusReply.Error("Item was already added to this shopping cart")
      result2.hasNoEvents shouldBe true
      result2.state shouldBe ShoppingCart.State(Map("foo" -> 42))
    }

    "reject an item with negative quantity" in {
      val result = eventSourcedTestKit.runCommand(replyTo => ShoppingCart.AddItem("foo", -42, replyTo))
      result.reply shouldBe StatusReply.Error("Quantity must be greater than zero")
      result.hasNoEvents shouldBe true
      result.state shouldBe ShoppingCart.State.empty
    }


  }

}
