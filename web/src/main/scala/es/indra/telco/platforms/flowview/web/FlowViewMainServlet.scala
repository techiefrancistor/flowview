package es.indra.telco.platforms.flowview.web

import org.slf4j.LoggerFactory
import org.scalatra._
import slick.driver.DerbyDriver.api._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import slick.jdbc.GetResult._

import org.scalatra.json._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.prefs.EmptyValueStrategy

// Definition of Sessions table
// timeMillis, bras, dslam, cdrRate, cdrRateChange, startCDRRatio, shortStopCDRRatio
class Sessions(tag: Tag) extends Table[(Long, String, String, Int)](tag, "SESSIONS") {
  def timeMillis = column[Long]("TIMEMILLIS")
  def bras = column[String]("BRAS")  // Column names must be capitalized
  def dslam = column[String]("DSLAM")
  def sessions = column[Int]("SESSIONS")

  // Every table needs a * projection with the same type as the table's type parameter
  def * = (timeMillis, bras, dslam, sessions)
}

// Definition of CDR table
// timeMillis, bras, dslam, cdrRate, cdrRateChange, startCDRRatio, shortStopCDRRatio
class CdrStats(tag: Tag) extends Table[(Long, String, String, Float, Float, Float, Float)](tag, "CDRSTATS") {
  def timeMillis = column[Long]("TIMEMILLIS")
  def bras = column[String]("BRAS")
  def dslam = column[String]("DSLAM")
  def cdrRate = column[Float]("CDRRATE")
  def cdrRateChange = column[Float]("CDRRATECHANGE")
  def startCDRRatio = column[Float]("STARTCDRRATIO")
  def shortStopCDRRatio = column[Float]("SHORTSTOPCDRRATIO")

  // Every table needs a * projection with the same type as the table's type parameter
  def * = (timeMillis, bras, dslam, cdrRate, cdrRateChange, startCDRRatio, shortStopCDRRatio)
}

class FlowViewMainServlet(db: Database) extends WebStack with JacksonJsonSupport {
  
  implicit val jsonFormats = new DefaultFormats {
    override val emptyValueStrategy: EmptyValueStrategy = EmptyValueStrategy.preserve
    override val allowNull: Boolean = true
  }
 
  val logger = LoggerFactory.getLogger(getClass)
  
  /**
   * Returns json object with number of sessions per DSLAM and per BRAS
   * by querying the Sessions table
   */
  get("/cdrStats/sessions"){
    
    logger.info("Requested /cdrStats/sessions")
    
    // Get sessions per dslam
    val sessionsTable = TableQuery[Sessions]
    val maxTime = sessionsTable.map(item => item.timeMillis).max
    val sessionsPerDslamQuery = sessionsTable.filter(item => { item.timeMillis === maxTime}).map(item => (item.bras, item.dslam, item.sessions))
    val sessionsPerDslam = Await.result(db.run(sessionsPerDslamQuery.result), Duration(5, "seconds"))
    
    // Get sessions per bras
    val sessionsPerBrasQuery = sessionsTable.filter(item => { item.timeMillis === maxTime}).groupBy(item => item.bras).map{case (bras, ag) => (bras, ag.map(_.sessions).sum)}
    val sessionsPerBras = Await.result(db.run(sessionsPerBrasQuery.result), Duration(5, "seconds"))
    
    // Used for conversion of sessions to Array of Arrays, instead of the map generated by Extraction.decompose
    // Used in the lines just below 
    implicit def sessionsPerDslamToJsonArray(gps: Seq[(String, String, Int)]): JValue = {
      JArray(for (point <- gps.toList) yield {
        JArray(List(point._2, point._1, point._3))
      })
    }

    implicit def sessionsPerBrasToJsonArray(gps: Seq[(String, Option[Int])]): JValue = {
      JArray(for (point <- gps.toList) yield {
        JArray(List(point._1, point._2.getOrElse[Int](0)))
      })
    }
    
   ("sessionsPerDslam" -> sessionsPerDslam) ~ ("sessionsPerBras" -> sessionsPerBras)

  }
  
  /**
   * Returns json object containing the events for which a significant increase or decrease
   * in the rate of CDR arrival has been detected. Each one of those events
   * is an array of <time-since-event>, <id of the dslam>, <-2|-1|1|2> this last value
   * informing of the increase/decrease of rate (-2 is < 50% of previous rate, and so on)
   * 
   * The json object includes also map of id to dlsam 
   */
  get("/cdrStats/cdrRateChange") {
    
    logger.info("Requested /cdrStats/cdrRateChange")
    
    val currentTime = new java.util.Date().getTime()
    
    val statsTable = TableQuery[CdrStats]
    
    // Sort by bras and dslam
    val statsQuery = statsTable.sortBy(stat => (stat.bras, stat.dslam)).map(stat => (stat.timeMillis, stat.dslam, stat.cdrRateChange))
    val stats = Await.result(db.run(statsQuery.result), Duration(5, "seconds"))
    val maxTime = stats.map(item => item._1).max
    
    // Build map of dslamIds. The first is used in this servlet, to generate dslam indices, and the
    // second is used in the web page, to get the dslam corresponding to a certain graph row
    val dslamToId = scala.collection.mutable.Map[String, Int]()
    val idToDslam = scala.collection.mutable.Map[Int, String]()
    var i = 0
    for(dslam <- stats.map(item => item._2).distinct){
      dslamToId(dslam) = i
      idToDslam(i) = dslam
      i = i + 1
    }
    
    // Build events array
    val events = scala.collection.mutable.ArrayBuffer[(Long, Int, Int)]()
    stats.map(stat => {
      val columnIndex = {
        if(stat._3 < -0.5) -2
        else if(stat._3 < -0.25) -1
        else if(stat._3 > 0.25) 1
        else if(stat._3 > 0.5) 2
        else 0
      }
      
      if(columnIndex != 0) events += (((currentTime - stat._1) / 1000, dslamToId(stat._2), columnIndex))
    })
    
    // Used for conversion of events to Array of Arrays, instead of the map generated by Extraction.decompose
    // Used in the line just below 
    implicit def graphPointsToJsonArray(gps: scala.collection.mutable.ArrayBuffer[(Long, Int, Int)]): JValue = {
      JArray(for (point <- gps.toList) yield {
        JArray(List(point._1, point._2, point._3))
      })
    }
    
    ("dslamMap" -> Extraction.decompose(idToDslam)) ~ ("events" -> events) ~ ("maxTime" -> ((currentTime - maxTime) / 1000))
  }
  
  /**
   * Returns json object containing historical data for cdr rate for a specific DSLAM
   */
  get("/cdrStats/cdrRateHist") {
    
    val dslam = params.get("dslam").getOrElse("10.0.0.1/2")
    
    logger.info("Requested /cdrStats/cdrRateHist for " +  dslam)
    
    val currentTime = new java.util.Date().getTime()
    
    val statsTable = TableQuery[CdrStats]
    val rateQuery = statsTable.filter(item => item.dslam === dslam).sortBy(item => item.timeMillis)
    val rateQueryResult = Await.result(db.run(rateQuery.result), Duration(5, "seconds"))
    
    // Build rates array
    val rates = scala.collection.mutable.ArrayBuffer[(Long, Float)]()
    rateQueryResult.map(stat => {
      rates += (((currentTime - stat._1) / 1000, stat._4))
    })
    
    // Used for conversion of rates to Array of Arrays, instead of the map generated by Extraction.decompose
    // Used in the line just below 
    implicit def graphPointsToJsonArray(gps: scala.collection.mutable.ArrayBuffer[(Long, Float)]): JValue = {
      JArray(for (point <- gps.toList) yield {
        JArray(List(point._1, point._2))
      })
    }
    
    val result: JObject = ("cdrRates" -> rates) ~ ("dslam" -> dslam)
    
    logger.debug(result.toString())    
    
    result
  }
}

