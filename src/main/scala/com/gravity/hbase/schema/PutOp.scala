package com.gravity.hbase.schema

import scala.collection.mutable.Buffer
import scala.collection.Map
import org.apache.hadoop.hbase.client.Put
import org.joda.time.DateTime
import org.apache.hadoop.hbase.client.Durability

/*             )\._.,--....,'``.
 .b--.        /;   _.. \   _\  (`._ ,.
`=,-,-'~~~   `----(,_..'--(,_..'`-.;.'  */

/**
 * A Put operation.  Can work across multiple columns or entire column families treated as Maps.
 * @param table
 * @param key
 * @param previous
 * @param writeToWAL
 * @tparam T
 * @tparam R
 */
class PutOp[T <: HbaseTable[T, R, _], R](table: HbaseTable[T, R, _], key: Array[Byte], previous: Buffer[OpBase[T, R]] = Buffer[OpBase[T, R]](), writeToWAL: Boolean = true) extends OpBase[T, R](table, key, previous) {
  val put = new Put(key)
  if (writeToWAL) put.setDurability(Durability.SYNC_WAL) else put.setDurability(Durability.SKIP_WAL)


  def +(that: OpBase[T, R]) = new PutOp(table,key, previous ++ that.previous, writeToWAL)

  def value[F, K, V](column: (T) => Column[T, R, F, K, V], value: V, timeStamp: DateTime = null) = {
    val col = column(table.asInstanceOf[T])
    if (timeStamp == null) {
      put.add(col.familyBytes, col.columnBytes, col.valueConverter.toBytes(value))
    } else {
      put.add(col.familyBytes, col.columnBytes, timeStamp.getMillis, col.valueConverter.toBytes(value))
    }
    this
  }

  def valueMap[F, K, V](family: (T) => ColumnFamily[T, R, F, K, V], values: Map[K, V]) = {
    val fam = family(table.pops)
    for ((key, value) <- values) {
      put.add(fam.familyBytes, fam.keyConverter.toBytes(key), fam.valueConverter.toBytes(value))
    }
    this
  }
}
