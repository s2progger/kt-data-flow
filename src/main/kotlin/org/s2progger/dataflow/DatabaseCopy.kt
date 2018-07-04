package org.s2progger.dataflow

import mu.KLogging
import org.s2progger.dataflow.config.DatabaseConnectionDetail
import org.s2progger.dataflow.config.DatabaseImport
import org.s2progger.dataflow.config.ExportDbConfiguration
import org.s2progger.dataflow.config.PostRunScript
import java.io.File
import java.sql.*
import java.text.NumberFormat

class DatabaseCopy(val exportConfig: ExportDbConfiguration) {
    companion object: KLogging()

    init {
        if (exportConfig.outputFolder != null) {
            File(exportConfig.outputFolder).mkdirs()
        }

        Class.forName(exportConfig.driver)
    }

    fun copyDatabase(dbName: String, details: DatabaseConnectionDetail) {
        Class.forName(details.driver)

        val exportUrl = when (exportConfig.outputFolder.isNullOrEmpty()) {
            true -> "${exportConfig.urlProtocol}${exportConfig.urlOptions}"
            false -> "${exportConfig.urlProtocol}${exportConfig.outputFolder}${dbName.toLowerCase().replace(" ", "_")}-import${exportConfig.urlOptions}"
        }

        val importConnection = DriverManager.getConnection(details.url, details.username, details.password)
        val exportConnection = DriverManager.getConnection(exportUrl, exportConfig.username, exportConfig.password)


        if (details.sqlSetupCommands != null) {
            val stmt = importConnection.createStatement()

            stmt.execute(details.sqlSetupCommands)
            stmt.close()
        }

        if (exportConfig.sqlSetupCommands != null) {
            val stmt = exportConnection.createStatement()

            stmt.execute(exportConfig.sqlSetupCommands)
            stmt.close()
        }

        importApplication(importConnection, exportConnection, details.imports)

        if (details.postScripts != null)
            runPostScripts(exportConnection, details.postScripts)
    }

    private fun importApplication(importDbConnection: Connection, exportDbConnection: Connection, importList: List<DatabaseImport>) {
        importDbConnection.autoCommit = false
        exportDbConnection.autoCommit = false

        val importMeta = importDbConnection.metaData

        // Attempt to use a forward moving cursor for result sets in order to cut down on memory usage when fetching millions of rows
        val importStatement = importDbConnection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
        val exportStatement = exportDbConnection.createStatement()

        System.out.println("Database product: ${importMeta.databaseProductName}")
        System.out.println("Database version: ${importMeta.databaseProductVersion}")

        for (import in importList) {
            System.out.println("Importing ${import.table}...")

            prepareImportTable(import.table, importStatement, exportStatement)

            importStatement.fetchSize = import.fetchSize ?: 10000

            importTable(import, importStatement, exportDbConnection)
        }

        importStatement.close()
        exportStatement.close()
    }

    private fun runPostScripts(connection: Connection, scripts: List<PostRunScript>) {
        val statement = connection.createStatement()

        for (script in scripts) {
            System.out.println("Running script: ${script.label}...")

            statement.executeUpdate(script.sql)

            System.out.println("${script.label} complete")
        }

        statement.close()
    }

    @Throws(Exception::class)
    private fun prepareImportTable(table: String, importStatement: Statement, exportStatement: Statement) {

        try {
            // Check if the table to import to already exists
            val tableDetectSql = "SELECT * FROM $table WHERE 1 = 2"

            exportStatement.executeQuery(tableDetectSql)
        } catch (e: Exception) {
            // Table doesn't exist, so create it
            val script = getTableCreateScript(table, importStatement)

            exportStatement.execute(script)
        }
    }

    @Throws(Exception::class)
    private fun getTableCreateScript(table: String, statement: Statement) : String {
        val script = StringBuffer()

        val tableDetectSql = "SELECT * FROM $table WHERE 1 = 2"
        val rs = statement.executeQuery(tableDetectSql)
        val meta = rs.metaData

        script.append("CREATE TABLE $table ( ")

        var first = true

        for (i in 1..meta.columnCount) {
            if (first) {
                first = false
            } else {
                script.append(", ")
            }

            val name = meta.getColumnName(i)
            val type = typeToTypeName(meta.getColumnType(i))
            val size = meta.getPrecision(i)
            val precision = meta.getScale(i)

            val nullable = if (meta.isNullable(i) == ResultSetMetaData.columnNoNulls) "NOT NULL" else ""

            if (isSizable(type) && isNumeric(type))
                script.append("$name $type ($size,$precision) $nullable")
            else if(isSizable(type))
                script.append("$name $type ($size) $nullable")
            else
                script.append("$name $type $nullable")
        }

        script.append(")")

        rs.close()

        return script.toString()
    }


    @Throws(Exception::class)
    private fun importTable(import: DatabaseImport, importStatement: Statement, exportConnection: Connection) {
        val insertBatchSize = exportConfig.exportBatchSize ?: 10000;
        val columnDetectSql = "SELECT * FROM ${import.table} WHERE 1 = 2"
        val selectSql = import.query ?: "SELECT * FROM ${import.table}"

        val metaRs = importStatement.executeQuery(columnDetectSql)
        val meta = metaRs.metaData
        val columnTypes = Array(meta.columnCount, { -1 })

        for (i in 1..meta.columnCount) {
            columnTypes[i - 1] = meta.getColumnType(i)
        }

        val insertSql = "INSERT INTO ${import.table} VALUES (${setupParameterList(meta.columnCount)})"

        metaRs.close()

        val rs = importStatement.executeQuery(selectSql)
        val ps = exportConnection.prepareStatement(insertSql)

        var rowCount = 0

        while (rs.next()) {
            rowCount++

            for (i in 1..columnTypes.count()) {
                when (columnTypes[i - 1]) {
                    Types.ARRAY -> ps.setArray(i, rs.getArray(i))
                    Types.BIGINT -> ps.setLong(i, rs.getLong(i))
                    Types.BINARY -> ps.setBinaryStream(i, rs.getBinaryStream(i))
                    Types.BIT -> ps.setBoolean(i, rs.getBoolean(i))
                    Types.BLOB -> ps.setBlob(i, rs.getBlob(i))
                    Types.CLOB -> ps.setString(i, rs.getString(i))
                    Types.BOOLEAN -> ps.setBoolean(i, rs.getBoolean(i))
                    Types.CHAR -> ps.setString(i, rs.getString(i))
                    Types.DATE -> ps.setDate(i, rs.getDate(i))
                    Types.DECIMAL -> ps.setBigDecimal(i, rs.getBigDecimal(i))
                    Types.DOUBLE -> ps.setDouble(i, rs.getDouble(i))
                    Types.FLOAT -> ps.setFloat(i, rs.getFloat(i))
                    Types.INTEGER -> ps.setInt(i, rs.getInt(i))
                    Types.NCHAR -> ps.setString(i, rs.getString(i))
                    Types.NUMERIC -> ps.setBigDecimal(i, rs.getBigDecimal(i))
                    Types.NVARCHAR -> ps.setString(i, rs.getString(i))
                    Types.ROWID -> ps.setLong(i, rs.getLong(i))
                    Types.SMALLINT -> ps.setShort(i, rs.getShort(i))
                    Types.SQLXML -> ps.setString(i, rs.getString(i))
                    Types.TIME -> ps.setTime(i, rs.getTime(i))
                    Types.TIMESTAMP -> ps.setTimestamp(i, rs.getTimestamp(i))
                    Types.TINYINT -> ps.setByte(i, rs.getByte(i))
                    Types.VARBINARY -> ps.setBytes(i, rs.getBytes(i))
                    Types.VARCHAR -> ps.setString(i, rs.getString(i))
                    Types.LONGVARBINARY -> ps.setBytes(i, rs.getBytes(i))
                    else -> ps.setBlob(i, rs.getBlob(i))
                }
            }

            ps.addBatch()

            if (rowCount % insertBatchSize == 0) {
                ps.executeBatch()
                exportConnection.commit()

                System.out.print("\rExported ${NumberFormat.getInstance().format(rowCount)} records so far...")
                System.out.flush()
            }
        }

        ps.executeBatch()
        exportConnection.commit()

        ps.close()
        rs.close()

        System.out.println("Processed ${NumberFormat.getInstance().format(rowCount)} record(s) from ${import.table}")
    }

    private fun setupParameterList(columns: Int) : String {
        val result = StringBuffer()

        var first = true

        for (i in 0 until columns) {
            if (first) {
                first = false
            } else {
                result.append(", ")
            }

            result.append("?")
        }

        return result.toString()
    }

    private fun typeToTypeName(type: Int) : String {
        return when (type) {
            Types.ARRAY     -> "ARRAY"
            Types.BIGINT    -> "BIGINT"
            Types.BINARY    -> "BINARY"
            Types.BIT       -> "BIT"
            Types.BLOB      -> "BLOB"
            Types.CLOB      -> "CLOB"
            Types.BOOLEAN   -> "BIT"
            Types.CHAR      -> "CHAR"
            Types.DATE      -> "DATE"
            Types.DECIMAL   -> "DECIMAL"
            Types.DOUBLE    -> "DOUBLE"
            Types.FLOAT     -> "FLOAT"
            Types.INTEGER   -> "INT"
            Types.NCHAR     -> "NCHAR"
            Types.NUMERIC   -> "NUMERIC"
            Types.NVARCHAR  -> "NVARCHAR"
            Types.ROWID     -> "BIGINT"
            Types.SMALLINT  -> "SMALLINT"
            Types.SQLXML    -> "BLOB"
            Types.TIME      -> "TIME"
            Types.TIMESTAMP -> "TIMESTAMP"
            Types.TINYINT   -> "TINYINT"
            Types.VARBINARY -> "VARBINARY"
            Types.VARCHAR   -> "VARCHAR"
            Types.LONGVARBINARY -> "VARBINARY(MAX)" // This won't work in Oracle
            else            -> "BLOB"
        }
    }

    private fun isSizable(type: String) : Boolean {
        val sizableTypes = arrayListOf("VARCHAR", "NUMERIC", "DECIMAL", "CHAR", "NCHAR", "NVARCHAR")

        return sizableTypes.contains(type)
    }

    private fun isNumeric(type: String) : Boolean {
        val numericTypes = arrayListOf("NUMERIC", "DECIMAL")

        return numericTypes.contains(type)
    }
}