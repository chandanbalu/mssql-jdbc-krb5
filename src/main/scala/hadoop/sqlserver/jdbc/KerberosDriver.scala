package hadoop.sqlserver.jdbc

import java.security.PrivilegedAction
import java.sql.{Connection, Driver, DriverPropertyInfo, DriverManager,SQLException}
import java.util.Properties
import java.util.logging.Logger
import scala.collection.JavaConverters._
import com.microsoft.sqlserver.jdbc.SQLServerDriver
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hdfs.server.common.JspHelper.Url
import org.apache.hadoop.security.UserGroupInformation
import org.apache.hadoop.security.UserGroupInformation.AuthenticationMethod

/**
 * Created by IntelliJ IDEA.
 *
 * @author : Chandan Balu
 * @created_date : 6/1/2021, Tue
 * */
class KerberosDriver extends Driver {
  /**
   * A class wrap the SQL Server JDBC driver, within the connection method call we get a kerberos ticket
   * and then call the actual SQL Server driver to return the connection logged in with this ticket.
   *
   * The code uses the UserGroupInformation from the Hadoop API to login from a keytab and the “doAs” call
   * to return the connection.
   *
   */

  private val sqlServerDriver = new SQLServerDriver()

  override def acceptsURL(url: String) = true

  override def jdbcCompliant(): Boolean = sqlServerDriver.jdbcCompliant()

  override def getPropertyInfo(url: String, info: Properties): Array[DriverPropertyInfo] = sqlServerDriver.getPropertyInfo(url, info)

  override def getMinorVersion: Int = sqlServerDriver.getMinorVersion

  override def getParentLogger: Logger = sqlServerDriver.getParentLogger

  /**
   *
   *  override def connect2(url: String, info: Properties): Connection = {
   *  val krbUrl = Krb5SqlServer.toSqlServerUrl(url)
   *  println("Providing connection through Krb5SqlServer")
   *  sqlServerDriver.connect(krbUrl, info)
   *  }
   */
  
  override def connect(url: String, info: Properties): Connection = {
    /**
     * In this method, we'll convert to the JDBC URL from our custom URL to MSSQL JDBC URL
     * within the  UserGroupInformation to use the principal and keytabFile
     */
     
    // Provid support to connect to MS SQL as local or cluster mode.
    val mode: String = if (info.getProperty("mode",null)==null) "Cluster" else "Local"
    getParentLogger.info("Providing connection through hadoop.sqlserver.jdbc.krb5.SQLServerDriver in " + mode +" mode" )
    if(mode.toLowerCase()=="local"){
      val krbUrl = KerberosDriver.toSqlServerUrl(url)
      return sqlServerDriver.connect(krbUrl, info)
    }
    val krbUrl = KerberosDriver.toSqlServerUrl(url)
    val connectionProps = KerberosDriver.connectionProperties(url)
    val keytabFile = connectionProps(KerberosDriver.keytabFile)
    val principal = connectionProps(KerberosDriver.principalKey)

    val config = new Configuration()
    config.addResource("/etc/hadoop/conf/hdfs-site.xml")
    config.addResource("/etc/hadoop/conf/core-site.xml")
    config.addResource("/etc/hadoop/conf/mapred-site.xml")

    UserGroupInformation.setConfiguration(config)

    UserGroupInformation
      .getCurrentUser
      .setAuthenticationMethod(AuthenticationMethod.KERBEROS)

    UserGroupInformation.loginUserFromKeytabAndReturnUGI(principal, keytabFile)
      .doAs(new PrivilegedAction[Connection] {
        override def run(): Connection =
          sqlServerDriver.connect(krbUrl, info)
    })
  }

  override def getMajorVersion: Int = sqlServerDriver.getMajorVersion
}

object KerberosDriver {
  // Method to convert the given URL to SQL Server URL
  def toSqlServerUrl(url: String): String = s"${head(url).replace(krbPrefix, sqlServerPrefix)};${connectionProperties(url).filter({case (k, v) => k != principalKey && k != keytabFile}).map({case (k, v) => s"$k=$v"}).mkString(";")};"

  val sqlServerPrefix = "sqlserver"
  val krbPrefix = "krb5ss"
  val principalKey = "krb5Principal"
  val keytabFile = "krb5Keytab"

  // Method to convert the given URL to Map
  def connectionProperties(url: String): Map[String, String] = url.split(';')
                                                                .toList.tail.map(p => p.split('=')).map(s => (s(0), s(1)))
                                                                .foldLeft(Map.empty[String, String]){ case (m, (k, v)) => m + (k -> v) }

  def head(url: String): String = url.split(';').head

}
