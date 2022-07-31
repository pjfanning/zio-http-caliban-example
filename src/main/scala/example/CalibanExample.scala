package example

import caliban.GraphQL.graphQL
import caliban.execution.Field
import caliban.schema.ArgBuilder
import caliban.schema.Annotations.GQLDescription
import caliban.{RootResolver, ZHttpAdapter}
import zhttp.http._
import zhttp.service.Server
import zio.{ExitCode, ZIO}
import io.getquill
import io.getquill._
import io.getquill.CalibanIntegration._
import io.getquill.context.qzio.ImplicitSyntax._
import io.getquill.context.ZioJdbc._
import io.getquill.util.{ContextLogger, LoadConfig}
import zio.Console.printLine
import zio.{ ZIOApp, ExitCode, URIO, Task }

import java.io.Closeable
import javax.sql.DataSource
import scala.language.postfixOps

object FlatSchema:
  case class PersonT(id: Int, first: String, last: String, age: Int)
  case class AddressT(ownerId: Int, street: String)
  case class PersonAddress(id: Int, first: String, last: String, age: Int, street: Option[String])
  object ExampleData:
    val people =
      List(
        PersonT(1, "One", "A", 44),
        PersonT(2, "Two", "B", 55),
        PersonT(3, "Three", "C", 66)
      )
    val addresses =
      List(
        AddressT(1, "123 St"),
        AddressT(2, "789 St")
      )
    val personAddress =
      List(
        PersonAddress(1, "One", "A", 44, Some("123 St")),
        PersonAddress(2, "Two", "B", 55, Some("123 St")),
        PersonAddress(3, "Three", "C", 66, None),
      )

object Dao:
  import FlatSchema._
  case class PersonAddressPlanQuery(plan: String, pa: List[PersonAddress])
  private val logger = ContextLogger(classOf[Dao.type])

  object Ctx extends PostgresZioJdbcContext(Literal)
  import Ctx._
  lazy val ds = JdbcContextConfig(LoadConfig("testPostgresDB")).dataSource
  given Implicit[DataSource] = Implicit(ds)

  inline def q(inline columns: List[String], inline filters: Map[String, String]) =
    quote {
      query[PersonT].leftJoin(query[AddressT]).on((p, a) => p.id == a.ownerId)
        .map((p, a) => PersonAddress(p.id, p.first, p.last, p.age, a.map(_.street)))
        .filterColumns(columns)
        .filterByKeys(filters)
        .take(10)
    }
  inline def plan(inline columns: List[String], inline filters: Map[String, String]) =
    quote { sql"EXPLAIN ${q(columns, filters)}".pure.as[Query[String]] }

  def personAddress(columns: List[String], filters: Map[String, String]) =
    println(s"Getting columns: $columns")
    run(q(columns, filters)).implicitDS.mapError(e => {
      logger.underlying.error("personAddress query failed", e)
      e
    })

  def personAddressPlan(columns: List[String], filters: Map[String, String]) =
    run(plan(columns, filters), OuterSelectWrap.Never).map(_.mkString("\n")).implicitDS.mapError(e => {
      logger.underlying.error("personAddressPlan query failed", e)
      e
    })

  def resetDatabase() =
    (for {
      _ <- run(sql"TRUNCATE TABLE AddressT, PersonT RESTART IDENTITY".as[Delete[PersonT]])
      _ <- run(liftQuery(ExampleData.people).foreach(row => query[PersonT].insertValue(row)))
      _ <- run(liftQuery(ExampleData.addresses).foreach(row => query[AddressT].insertValue(row)))
    } yield ()).implicitDS
end Dao

object CalibanExample extends zio.ZIOAppDefault:
  import FlatSchema._

  case class Queries(
                      personAddress: Field => (ProductArgs[PersonAddress] => Task[List[PersonAddress]]),
                      personAddressPlan: Field => (ProductArgs[PersonAddress] => Task[Dao.PersonAddressPlanQuery])
                    )

  val endpoints =
    graphQL(
      RootResolver(
        Queries(
          personAddress =>
            (productArgs =>
              Dao.personAddress(quillColumns(personAddress), productArgs.keyValues)
              ),
          personAddressPlan =>
            (productArgs => {
              val cols = quillColumns(personAddressPlan)
              (Dao.personAddressPlan(cols, productArgs.keyValues) zip Dao.personAddress(cols, productArgs.keyValues)).map(
                (pa, plan) => Dao.PersonAddressPlanQuery(pa, plan)
              )
            })
        )
      )
    ).interpreter

  val myApp = for {
    _ <- Dao.resetDatabase()
    interpreter <- endpoints
    _ <- Server.start(
      port = 8088,
      http = Http.collectHttp[Request] { case _ -> !! / "api" / "graphql" =>
        ZHttpAdapter.makeHttpService(interpreter)
      }
    )
      .forever
  } yield ()

  override def run =
    myApp.exitCode

end CalibanExample