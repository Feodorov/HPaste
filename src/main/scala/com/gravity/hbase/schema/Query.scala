/**Licensed to Gravity.com under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Gravity.com licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gravity.hbase.schema

import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.util._
import scala.collection.JavaConversions._
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp
import scala.collection._
import immutable.TreeSet
import java.util.NavigableSet
import scala.collection.mutable.Buffer
import org.apache.hadoop.hbase.filter.FilterList.Operator
import org.joda.time.{ReadableInstant, DateTime}
import org.apache.hadoop.hbase.filter._


/*             )\._.,--....,'``.
.b--.        /;   _.. \   _\  (`._ ,.
`=,-,-'~~~   `----(,_..'--(,_..'`-.;.'  */


/**
 * Expresses a scan, get, or batched get against hbase.  Which one it becomes depends on what
 * calls you make.  If you specify withKey() it will
 * become a Get, withKeys() will make into a batched get, and no keys at all will make it a Scan.
 *
 * @tparam T the table to work with
 * @tparam R the row key type
 * @tparam RR the row result type
 */
trait BaseQuery[T <: HbaseTable[T, R, RR], R, RR <: HRow[T, R]] {

  val table: HbaseTable[T, R, RR]
  val keys = mutable.Buffer[Array[Byte]]()
  val families = mutable.Buffer[Array[Byte]]()
  val columns = mutable.Buffer[(Array[Byte], Array[Byte])]()
  var currentFilter: FilterList = _
  // new FilterList(Operator.MUST_PASS_ALL)
  var startRowBytes: Array[Byte] = null
  var endRowBytes: Array[Byte] = null
  var batchSize = -1
  var startTime: Long = Long.MinValue
  var endTime: Long = Long.MaxValue

  def filter(filterFx: ((FilterBuilder) => Unit)*): this.type = {
    val fb = new FilterBuilder(true)
    for (fx <- filterFx) {
      fx(fb)
    }
    currentFilter = fb.coreList
    this
  }

  def filterOr(filterFx: ((FilterBuilder) => Unit)*): this.type = {
    val fb = new FilterBuilder(false)
    for (fx <- filterFx) {
      fx(fb)
    }
    currentFilter = fb.coreList
    this

  }


  class FilterBuilder(and: Boolean) {
    var coreList: FilterList = if (and) new FilterList(Operator.MUST_PASS_ALL) else new FilterList(Operator.MUST_PASS_ONE)
    val clauseBuilder = new ClauseBuilder()

    private def addFilter(filter: FilterList) {
      //coreList = filter
      coreList.addFilter(filter)
    }


    def or(clauses: ((ClauseBuilder) => Option[Filter])*) = {
      val orFilter = new FilterList(FilterList.Operator.MUST_PASS_ONE)
      for (ctx <- clauses) {
        val filter = ctx(clauseBuilder)
        if (filter.isDefined) {
          orFilter.addFilter(filter.get)
        }
      }
      if (orFilter.getFilters().size() > 0) {
        addFilter(orFilter)
      }
      this
    }

    def and(clauses: ((ClauseBuilder) => Option[Filter])*) = {
      val andFilter = new FilterList(FilterList.Operator.MUST_PASS_ALL)
      for (cfx <- clauses) {
        val filter = cfx(clauseBuilder)
        if (filter.isDefined) {
          andFilter.addFilter(filter.get)
        }
      }
      if (andFilter.getFilters.size() > 0) {
        addFilter(andFilter)
      }
      this
    }

  }

  class ClauseBuilder() {

    def columnValueMustStartWith[F, K, V](column: (T) => Column[T, R, F, K, String], prefix: String) = {
      val c = column(table.pops)
      val prefixFilter = new BinaryPrefixComparator(Bytes.toBytes(prefix))
      val vc = new SingleColumnValueFilter(c.familyBytes, c.columnBytes, CompareOp.EQUAL, prefixFilter)
      Some(vc)
    }


    def noClause = None

    def columnValueMustContain[F, K, V](column: (T) => Column[T, R, F, K, String], substr: String) = {
      val c = column(table.pops)
      val substrFilter = new SubstringComparator(substr)
      val vc = new SingleColumnValueFilter(c.familyBytes, c.columnBytes, CompareOp.EQUAL, substrFilter)
      Some(vc)
    }

    /**
     * Untested
     */
    def whereFamilyHasKeyGreaterThan[F, K](family: (T) => ColumnFamily[T, R, F, K, _], key: K) = {
      val f = family(table.pops)
      val fl = new FilterList(Operator.MUST_PASS_ALL)
      val ts = new QualifierFilter(CompareOp.GREATER_OR_EQUAL, new BinaryComparator(f.keyConverter.toBytes(key)))
      val ff = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(f.familyBytes))
      fl.addFilter(ts)
      fl.addFilter(ff)
      val sk = new SkipFilter(fl)
      Some(sk)
    }

    def columnValueMustPassRegex[F, K, V](column: (T) => Column[T, R, F, K, String], regex: String) = {
      val c = column(table.pops)
      val regexFilter = new RegexStringComparator(regex)
      val vc = new SingleColumnValueFilter(c.familyBytes, c.columnBytes, CompareOp.EQUAL, regexFilter)
      Some(vc)
    }


    def columnValueMustNotContain[F, K, V](column: (T) => Column[T, R, F, K, String], substr: String) = {
      val c = column(table.pops)
      val substrFilter = new SubstringComparator(substr)
      val vc = new SingleColumnValueFilter(c.familyBytes, c.columnBytes, CompareOp.NOT_EQUAL, substrFilter)
      Some(vc)
    }


    def maxRowsPerServer(rowsize: Int): Option[Filter] = {
      val pageFilter = new PageFilter(rowsize)
      Some(pageFilter)
    }

    def columnValueMustEqual[F, K, V](column: (T) => Column[T, R, F, K, V], value: V) = {
      val c = column(table.pops)
      val vc = new SingleColumnValueFilter(c.familyBytes, c.columnBytes, CompareOp.EQUAL, c.valueConverter.toBytes(value))
      vc.setFilterIfMissing(true)
      vc.setLatestVersionOnly(true)
      Some(vc)
    }

    def columnValueMustBeGreaterThan[F, K, V](column: (T) => Column[T, R, F, K, V], value: V) = {
      val c = column(table.pops)
      val vc = new SingleColumnValueFilter(c.familyBytes, c.columnBytes, CompareOp.GREATER, c.valueConverter.toBytes(value))
      vc.setFilterIfMissing(true)
      vc.setLatestVersionOnly(true)
      Some(vc)
    }

    def columnValueMustBeLessThan[F, K, V](column: (T) => Column[T, R, F, K, V], value: V) = {
      val c = column(table.pops)
      val vc = new SingleColumnValueFilter(c.familyBytes, c.columnBytes, CompareOp.LESS, c.valueConverter.toBytes(value))
      vc.setFilterIfMissing(true)
      vc.setLatestVersionOnly(true)
      Some(vc)
    }


    def columnValueMustBePresent[F, K, V](column: (T) => Column[T, R, F, K, V]) = {
      val c = column(table.pops)
      val vc = new SingleColumnValueFilter(c.familyBytes, c.columnBytes, CompareOp.NOT_EQUAL, Bytes.toBytes(0))
      vc.setFilterIfMissing(true)
      vc.setLatestVersionOnly(true)
      Some(new SkipFilter(vc))
    }

    def lessThanColumnKey[F, K, V](family: (T) => ColumnFamily[T, R, F, K, V], value: K) = {
      val fam = family(table.pops)
      val valueFilter = new QualifierFilter(CompareOp.LESS_OR_EQUAL, new BinaryComparator(fam.keyConverter.toBytes(value)))
      val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(family(table.pops).familyBytes))
      val andFilter = new FilterList(Operator.MUST_PASS_ALL)
      andFilter.addFilter(familyFilter)
      andFilter.addFilter(valueFilter)
      Some(andFilter)
    }

    def greaterThanColumnKey[F, K, V](family: (T) => ColumnFamily[T, R, F, K, V], value: K) = {
      val fam = family(table.pops)
      val andFilter = new FilterList(Operator.MUST_PASS_ALL)
      val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(fam.familyBytes))
      val valueFilter = new QualifierFilter(CompareOp.GREATER_OR_EQUAL, new BinaryComparator(fam.keyConverter.toBytes(value)))
      andFilter.addFilter(familyFilter)
      andFilter.addFilter(valueFilter)
      Some(andFilter)
    }

    //  def columnFamily[F,K,V](family: (T) => ColumnFamily[T,R,F,K,V])(implicit c: ByteConverter[F]): Query[T,R] = {
    //    val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(family(table.pops).familyBytes))
    //    currentFilter.addFilter(familyFilter)
    //    this
    //  }


    def whereColumnMustExist[F, K, _](column: (T) => Column[T, R, F, K, _]) = {
      val c = column(table.pops)
      val valFilter = new SingleColumnValueExcludeFilter(c.familyBytes, c.columnBytes, CompareOp.NOT_EQUAL, new Array[Byte](0))
      valFilter.setFilterIfMissing(true)
      Some(valFilter)
    }


    def betweenColumnKeys[F, K, V](family: (T) => ColumnFamily[T, R, F, K, V], lower: K, upper: K) = {
      val fam = family(table.pops)
      val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(fam.familyBytes))
      val begin = new QualifierFilter(CompareOp.GREATER_OR_EQUAL, new BinaryComparator(fam.keyConverter.toBytes(lower)))
      val end = new QualifierFilter(CompareOp.LESS_OR_EQUAL, new BinaryComparator(fam.keyConverter.toBytes(upper)))


      val filterList = new FilterList(Operator.MUST_PASS_ALL)
      filterList.addFilter(familyFilter)
      filterList.addFilter(begin)
      filterList.addFilter(end)
      Some(filterList)
    }

    def inFamily[F](family: (T) => ColumnFamily[T, R, F, _, _]) = {
      val fam = family(table.pops)
      val ff = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(fam.familyBytes))
      Some(ff)
    }

    def allInFamilies[F](familyList: ((T) => ColumnFamily[T, R, F, _, _])*) = {
      val filterList = new FilterList(Operator.MUST_PASS_ONE)
      for (family <- familyList) {
        val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(family(table.pops).familyBytes))
        filterList.addFilter(familyFilter)
      }
      Some(filterList)
    }

    /**
     * Limits the amount of columns returned within a single family where `pageSize` is the number of columns desired and `pageOffset` is the starting column index
     * @param family the family which contains the columns desired to be paged
     * @param pageSize the maximum number of columns to be returned for this family
     * @param pageOffset the offset that is to represent the first column of this page to return
     * @tparam F the family's type
     * @tparam K the family's key type
     * @tparam V the family's value type
     */
    def withPaginationForFamily[F, K, V](family: (T) => ColumnFamily[T, R, F, K, V], pageSize: Int, pageOffset: Int) = {
      val fam = family(table.pops)
      val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(fam.familyBytes))
      val paging = new ColumnPaginationFilter(pageSize, pageOffset)

      val filterList = new FilterList(Operator.MUST_PASS_ALL)
      filterList.addFilter(familyFilter)
      filterList.addFilter(paging)

      Some(filterList)
    }
  }

  /**The key to fetch (this makes it into a Get request against hbase) */
  def withKey(key: R): this.type = {
    keys += table.rowKeyConverter.toBytes(key)
    this
  }

  /**Multiple keys to fetch (this makes it into a multi-Get request against hbase) */
  def withKeys(keys: Set[R]): this.type = {
    for (key <- keys) {
      withKey(key)
    }
    this
  }


  def and: this.type = {
    if (currentFilter == null) {
      currentFilter = new FilterList(Operator.MUST_PASS_ALL)
    } else {
      val encompassingFilter = new FilterList(Operator.MUST_PASS_ALL)
      encompassingFilter.addFilter(currentFilter)
      currentFilter = encompassingFilter
    }
    this
  }

  def or: this.type = {
    if (currentFilter == null) {
      currentFilter = new FilterList(Operator.MUST_PASS_ONE)
    } else {
      val encompassingFilter = new FilterList(Operator.MUST_PASS_ONE)
      encompassingFilter.addFilter(currentFilter)
      currentFilter = encompassingFilter
    }
    this
  }

  def columnValueMustEqual[F, K, V](column: (T) => Column[T, R, F, K, V], value: V): this.type = {
    val c = column(table.pops)
    val vc = new SingleColumnValueFilter(c.familyBytes, c.columnBytes, CompareOp.EQUAL, c.valueConverter.toBytes(value))
    vc.setFilterIfMissing(true)
    vc.setLatestVersionOnly(true)
    currentFilter.addFilter(vc)
    this
  }

  def lessThanColumnKey[F, K, V](family: (T) => ColumnFamily[T, R, F, K, V], value: K): this.type = {
    val fam = family(table.pops)
    val valueFilter = new QualifierFilter(CompareOp.LESS_OR_EQUAL, new BinaryComparator(fam.keyConverter.toBytes(value)))
    val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(family(table.pops).familyBytes))
    val andFilter = new FilterList(Operator.MUST_PASS_ALL)
    andFilter.addFilter(familyFilter)
    andFilter.addFilter(valueFilter)
    currentFilter.addFilter(andFilter)
    this
  }

  def greaterThanColumnKey[F, K, V](family: (T) => ColumnFamily[T, R, F, K, V], value: K): this.type = {
    val fam = family(table.pops)
    val andFilter = new FilterList(Operator.MUST_PASS_ALL)
    val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(fam.familyBytes))
    val valueFilter = new QualifierFilter(CompareOp.GREATER_OR_EQUAL, new BinaryComparator(fam.keyConverter.toBytes(value)))
    andFilter.addFilter(familyFilter)
    andFilter.addFilter(valueFilter)
    currentFilter.addFilter(andFilter)
    this
  }

  //  def columnFamily[F,K,V](family: (T) => ColumnFamily[T,R,F,K,V])(implicit c: ByteConverter[F]): Query[T,R] = {
  //    val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(family(table.pops).familyBytes))
  //    currentFilter.addFilter(familyFilter)
  //    this
  //  }


  def betweenColumnKeys[F, K, V](family: (T) => ColumnFamily[T, R, F, K, V], lower: K, upper: K): this.type = {
    val fam = family(table.pops)
    val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(fam.familyBytes))
    val begin = new QualifierFilter(CompareOp.GREATER_OR_EQUAL, new BinaryComparator(fam.keyConverter.toBytes(lower)))
    val end = new QualifierFilter(CompareOp.LESS_OR_EQUAL, new BinaryComparator(fam.keyConverter.toBytes(upper)))
    val filterList = new FilterList(Operator.MUST_PASS_ALL)
    filterList.addFilter(familyFilter)
    filterList.addFilter(begin)
    filterList.addFilter(end)
    currentFilter.addFilter(filterList)

    this
  }

  def allInFamilies[F](familyList: ((T) => ColumnFamily[T, R, F, _, _])*): this.type = {
    val filterList = new FilterList(Operator.MUST_PASS_ONE)
    for (family <- familyList) {
      val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(family(table.pops).familyBytes))
      filterList.addFilter(familyFilter)
    }
    currentFilter.addFilter(filterList)
    this
  }

  def betweenDates(start: ReadableInstant, end: ReadableInstant): this.type = {
    startTime = start.getMillis
    endTime = end.getMillis
    this
  }

  def afterDate(start: ReadableInstant): this.type = {
    startTime = start.getMillis
    this
  }

  def untilDate(end: ReadableInstant): this.type = {
    endTime = end.getMillis
    this
  }

  def withStartRow(row: R): this.type = {
    startRowBytes = table.rowKeyConverter.toBytes(row)
    this
  }

  def withEndRow(row: R): this.type = {
    endRowBytes = table.rowKeyConverter.toBytes(row)
    this
  }

  def withBatchSize(size: Int): this.type = {
    batchSize = size
    this
  }

}

trait MinimumFiltersToExecute[T <: HbaseTable[T, R, RR], R, RR <: HRow[T, R]] {

  this: BaseQuery[T, R, RR] =>

  def withFamilies[F](firstFamily: (T) => ColumnFamily[T, R, F, _, _], familyList: ((T) => ColumnFamily[T, R, F, _, _])*): Query[T, R, RR]

  def withColumnsInFamily[F, K, V](family: (T) => ColumnFamily[T, R, F, K, V], firstColumn: K, columnList: K*): Query[T, R, RR]

  @deprecated("withColumnsInFamily can select one or more columns from a single family", "0.1.24")
  def withColumn[F, K, V](family: (T) => ColumnFamily[T, R, F, K, V], columnName: K): Query[T, R, RR]

  @deprecated("withColumns can select one or more columns", "0.1.24")
  def withColumn[F, K, V](column: (T) => Column[T, R, F, K, V]): Query[T, R, RR]

  def withColumns[F, K, V](firstColumn: (T) => Column[T, R, F, _, _], columnList: ((T) => Column[T, R, F, _, _])*): Query[T, R, RR]

}

class Query[T <: HbaseTable[T, R, RR], R, RR <: HRow[T, R]] private(
                                                                                override val table: HbaseTable[T, R, RR],
                                                                                override val keys: Buffer[Array[Byte]],
                                                                                override val families: Buffer[Array[Byte]],
                                                                                override val columns: Buffer[(Array[Byte], Array[Byte])]) extends BaseQuery[T, R, RR] with MinimumFiltersToExecute[T, R, RR] {

  private[schema] def this(
                              table: HbaseTable[T, R, RR],
                              keys: Buffer[Array[Byte]] = Buffer[Array[Byte]](),
                              families: Buffer[Array[Byte]] = Buffer[Array[Byte]](),
                              columns: Buffer[(Array[Byte], Array[Byte])] = Buffer[(Array[Byte], Array[Byte])](),
                              currentFilter: FilterList,
                              startRowBytes: Array[Byte],
                              endRowBytes: Array[Byte],
                              batchSize: Int,
                              startTime: Long,
                              endTime: Long) = {
    this(table, keys, families, columns)
    this.currentFilter = currentFilter
    this.startRowBytes = startRowBytes
    this.endRowBytes = endRowBytes
    this.batchSize = batchSize
    this.startTime = startTime
    this.endTime = endTime
  }

  override def withFamilies[F](firstFamily: (T) => ColumnFamily[T, R, F, _, _], familyList: ((T) => ColumnFamily[T, R, F, _, _])*) = {
    for (family <- firstFamily +: familyList) {
      val fam = family(table.pops)
      families += fam.familyBytes
    }
    this
  }

  override def withColumnsInFamily[F, K, V](family: (T) => ColumnFamily[T, R, F, K, V], firstColumn: K, columnList: K*) = {
    val fam = family(table.pops)
    for (column <- firstColumn +: columnList) {
      columns += (fam.familyBytes -> fam.keyConverter.toBytes(column))
    }
    this
  }

  override def withColumn[F, K, V](family: (T) => ColumnFamily[T, R, F, K, V], columnName: K) = {
    val fam = family(table.pops)
    columns += (fam.familyBytes -> fam.keyConverter.toBytes(columnName))
    this
  }

  override def withColumn[F, K, V](column: (T) => Column[T, R, F, K, V]) = {
    val col = column(table.pops)
    columns += (col.familyBytes -> col.columnBytes)
    this
  }

  override def withColumns[F, K, V](firstColumn: (T) => Column[T, R, F, _, _], columnList: ((T) => Column[T, R, F, _, _])*) = {
    for (column <- firstColumn +: columnList) {
      val col = column(table.pops)
      columns += (col.familyBytes -> col.columnBytes)
    }
    this
  }

  def single(tableName: String = table.tableName, ttl: Int = 30, skipCache: Boolean = true) = singleOption(tableName, ttl, skipCache, false).get



  /**
   *
   * @param tableName
   * @param ttl
   * @param skipCache
   * @param noneOnEmpty If true, will return None if the result is empty.  If false, will return an empty row.  If caching is true, will cache the empty row.
   * @return
   */
  def singleOption(tableName: String = table.tableName, ttl: Int = 30, skipCache: Boolean = true, noneOnEmpty: Boolean = true): Option[RR] = {
    require(keys.size == 1, "Calling single() with more than one key")
    require(keys.size >= 1, "Calling a Get operation with no keys specified")
    val get = new Get(keys.head)
    get.setMaxVersions(1)

    if (startTime != Long.MinValue || endTime != Long.MaxValue) {
      get.setTimeRange(startTime, endTime)
    }

    // add all families
    families.foreach{ case family => get.addFamily(family) }

    // let's copy the families into a set using a comparator that is Array[Byte] friendly
    // This isn't just for performance, but more so just to have the comparator for Array[Byte] for the .contains() below
    val familySet = new TreeSet[Array[Byte]]()(Ordering.comparatorToOrdering(Bytes.BYTES_COMPARATOR)) ++ families

    // add remaining columns not called out in a families above
    for { (family, column) <- columns } yield {
      if (!familySet.contains(family)) {
        get.addColumn(family, column)
      }
    }

    if (currentFilter != null && currentFilter.getFilters.size() > 0) {
      get.setFilter(currentFilter)
    }

    val fromCache = if (skipCache) None else table.cache.getResult(get)

    fromCache match {
      case Some(result) => Some(result)
      case None => {
        table.withTableOption(tableName) {
          case Some(htable) => {
            val result = htable.get(get)
            if (noneOnEmpty && result.isEmpty) {
              None
            } else if(!noneOnEmpty && result.isEmpty) {
              val qr = table.emptyRow(keys.head)
              if(!skipCache) table.cache.putResult(get, qr, ttl)
              Some(qr)
            } else {
              val qr = table.buildRow(result)
              if (!skipCache && !result.isEmpty) table.cache.putResult(get, qr, ttl)
              Some(qr)
            }
          }
          case None => None
        }
      }
    }

  }

  def execute(tableName: String = table.tableName, ttl: Int = 30, skipCache: Boolean = true): Seq[RR] = {
    require(!keys.isEmpty, "execute assumes that you have called withKeys() or withKey().  If you are trying to do a scan, you should call Scan()")

    val results = Buffer[RR]() // buffer for storing all results retrieved

    // if we are utilizing cache, we'll need to be able to recall the `Get' later to use as the cache key
    val getsByKey = if (skipCache) mutable.Map.empty[String, Get] else mutable.Map[String, Get]()

    if (!skipCache) getsByKey.sizeHint(keys.size) // perf optimization

    // buffer for all `Get's that really need to be gotten
    val cacheMisses = Buffer[Get]()

    val gets = buildGetsAndCheckCache(skipCache) {
      case (get: Get, key: Array[Byte]) => if (!skipCache) getsByKey.put(new String(key), get)
    } {
      case (qropt: Option[RR], get: Get) => if (!skipCache) {
        qropt match {
          case Some(result) => results += result // got it! place it in our result buffer
          case None => cacheMisses += get // missed it! place the get in the buffer
        }
      }
    }

    // identify what still needs to be `Get'ed ;-}
    val hbaseGets = if (skipCache) gets else cacheMisses

    if (!hbaseGets.isEmpty) {
      // only do this if we have something to do
      table.withTable(tableName) {
        htable =>
          htable.get(hbaseGets).foreach(res => {
            if (res != null && !res.isEmpty) {
              // ignore empty results
              val qr = table.buildRow(res) // construct query result

              // now is where we need to retrive the 'get' used for this result so that we can
              // pass this 'get' as the key for our local cache
              if (!skipCache) table.cache.putResult(getsByKey(new String(res.getRow)), qr, ttl)
              results += qr // place it in our result buffer
            }
          })
      }
    }

    results.toSeq // DONE!
  }


  /**si
   * @param tableName
   * @param ttl
   * @param skipCache
   * @param returnEmptyRows If this is on, then empty rows will be returned and cached.  Often times empty rows are not considered in caching situations
   *                        so this is off by default to keep the mental model simple.
   * @return
   */
  def executeMap(tableName: String = table.tableName, ttl: Int = 30, skipCache: Boolean = true, returnEmptyRows:Boolean=false): Map[R, RR] = {
    require(!keys.isEmpty, "executeMap assumes that you have called withKeys() or withKey().  If you are trying to do a scan, you should call Scan()")

    // init our result map and give it a hint of the # of keys we have
    val resultMap = mutable.Map[R, RR]()

    if(returnEmptyRows) {
      for(key <- keys) {
         resultMap(table.rowKeyConverter.fromBytes(key)) = table.emptyRow(key)
      }
    }

    resultMap.sizeHint(keys.size) // perf optimization

    // if we are utilizing cache, we'll need to be able to recall the `Get' later to use as the cache key
    val getsByKey = if (skipCache) mutable.Map.empty[String, Get] else mutable.Map[String, Get]()

    if (!skipCache) getsByKey.sizeHint(keys.size) // perf optimization

    // buffer for all `Get's that really need to be gotten
    val cacheMisses = Buffer[Get]()

    val gets = buildGetsAndCheckCache(skipCache) {
      case (get: Get, key: Array[Byte]) => if (!skipCache) getsByKey.put(new String(key), get)
    } {
      case (qropt: Option[RR], get: Get) => if (!skipCache) {
        qropt match {
          case Some(result) => resultMap.put(result.rowid, result) // got it! place it in our result map
          case None => cacheMisses += get // missed it! place the get in the buffer
        }
      }
    }

    // identify what still needs to be `Get'ed ;-}
    val hbaseGets = if (skipCache) gets else cacheMisses

    if (!hbaseGets.isEmpty) {
      // only do this if we have something to do
      table.withTable(tableName) {
        htable =>
          htable.get(hbaseGets).foreach(res => {
            if (res != null && !res.isEmpty) {
              // ignore empty results
              val qr = table.buildRow(res) // construct query result

              // now is where we need to retrive the 'get' used for this result so that we can
              // pass this 'get' as the key for our local cache
              if (!skipCache) table.cache.putResult(getsByKey(new String(res.getRow)), qr, ttl)
              resultMap(qr.rowid) = qr // place it in our result map
            }
          })
      }
    }

    if(returnEmptyRows) {
      resultMap.foreach{case (key: R, row: RR) =>
        if(row.result.isEmpty) {
          if(!skipCache) {
            table.cache.putResult(getsByKey(new String(table.rowKeyConverter.toBytes(key))), row, ttl)
          }
        }
      }
    }

    resultMap // DONE!
  }

  private def buildGetsAndCheckCache(skipCache: Boolean)(receiveGetAndKey: (Get, Array[Byte]) => Unit = (get, key) => {})(receiveCachedResult: (Option[RR], Get) => Unit = (qr, get) => {}): Seq[Get] = {
    if (keys.isEmpty) return Seq.empty[Get] // no keys..? nothing to see here... move along... move along.

    val gets = Buffer[Get]() // buffer for the raw `Get's

    for (key <- keys) {
      val get = new Get(key)
      if (startTime != Long.MinValue || endTime != Long.MaxValue) {
        get.setTimeRange(startTime, endTime)
      }


      gets += get
      receiveGetAndKey(get, key)
    }

    // since the families and columns will be identical for all `Get's, only build them once
    val firstGet = gets(0)

    if (currentFilter != null && currentFilter.getFilters.size() > 0) {
      firstGet.setFilter(currentFilter)
    }


    var pastFirst = false
    for (get <- gets) {
      if (pastFirst) {
        // we want to skip the first `Get' as it already has families/columns

        // for all subsequent `Get's, we will build their familyMap from the first `Get'
        firstGet.getFamilyMap.foreach((kv: (Array[Byte], NavigableSet[Array[Byte]])) => {
          get.getFamilyMap.put(kv._1, kv._2)
        })
        if (currentFilter != null && currentFilter.getFilters.size() > 0) {
          get.setFilter(currentFilter)
        }
      } else {
        pastFirst = true
      }

      // try the cache with this filled in get
//      if (!skipCache) receiveCachedResult(table.cache.getResult(get), get)
    }

    if(!skipCache) {
      val cacheResults = table.cache.getResults(gets)

      cacheResults.foreach {case (get, rowOpt) =>
        receiveCachedResult(rowOpt,get)
      }
    }
    gets
  }

  def makeScanner(maxVersions: Int = 1, cacheBlocks: Boolean = true, cacheSize: Int = 100) = {
    require(keys.size == 0, "A scanner should not specify keys, use singleOption or execute or executeMap")
    val scan = new Scan()
    scan.setMaxVersions(maxVersions)
    scan.setCaching(cacheSize)
    scan.setCacheBlocks(cacheBlocks)

    if (batchSize > -1) {
      scan.setBatch(batchSize)
    }

    if (startTime != Long.MinValue || endTime != Long.MaxValue) {
      scan.setTimeRange(startTime, endTime)
    }

    if (startRowBytes != null) {
      scan.setStartRow(startRowBytes)
    }
    if (endRowBytes != null) {
      scan.setStopRow(endRowBytes)
    }

    // add all families
    families.foreach{ case family => scan.addFamily(family) }

    // let's copy the families into a set using a comparator that is Array[Byte] friendly
    // This isn't just for performance, but more so just to have the comparator for Array[Byte] for the .contains() below
    val familySet = new TreeSet[Array[Byte]]()(Ordering.comparatorToOrdering(Bytes.BYTES_COMPARATOR)) ++ families

    // add remaining columns not called out in a families above
    for { (family, column) <- columns } yield {
      if (!familySet.contains(family)) {
        scan.addColumn(family, column)
      }
    }

    if (currentFilter != null && currentFilter.getFilters.size > 0) {
      scan.setFilter(currentFilter)
    }

    scan
  }

  def scan(handler: (RR) => Unit, maxVersions: Int = 1, cacheBlocks: Boolean = true, cacheSize: Int = 100, useLocalCache: Boolean = false, localTTL: Int = 30) {
    val scan = makeScanner(maxVersions, cacheBlocks, cacheSize)

    val results = if (useLocalCache) Buffer[RR]() else Buffer.empty[RR]

    def cacheHandler(rr: RR) {
      if (useLocalCache) results += rr
    }

    def cacheComplete() {
      if (useLocalCache && !results.isEmpty) table.cache.putScanResult(scan, results.toSeq, localTTL)
    }

    val whatWeGetFromCache = if (useLocalCache) table.cache.getScanResult(scan) else None

    whatWeGetFromCache match {
      case Some(result) => {
        println("cache hit against key " + scan.toString)
        result.foreach(handler)
      }
      case None => {

        table.withTable() {
          htable =>

            val scanner = htable.getScanner(scan)

            try {
              var done = false
              while (!done) {
                val result = scanner.next()
                if (result != null) {
                  val rr = table.buildRow(result)
                  cacheHandler(rr)
                  handler(rr)
                } else {
                  done = true
                }
              }
            } finally {
              cacheComplete()
              scanner.close()
            }
        }
      }
    }

  }

  def scanToIterable[I](handler: (RR) => I, maxVersions: Int = 1, cacheBlocks: Boolean = true, cacheSize: Int = 100, useLocalCache: Boolean = false, localTTL: Int = 30) = {
    val scan = makeScanner(maxVersions, cacheBlocks, cacheSize)

    val results = if (useLocalCache) Buffer[RR]() else Buffer.empty[RR]

    def cacheHandler(rr: RR) {
      if (useLocalCache) results += rr
    }

    def cacheComplete() {
      if (useLocalCache && !results.isEmpty) table.cache.putScanResult(scan, results.toSeq, localTTL)
    }

    val whatWeGetFromCache = if (useLocalCache) table.cache.getScanResult(scan) else None

    val results2 = whatWeGetFromCache match {
      case Some(rrs) => rrs.map(rr => handler(rr))
      case None => {
        val runResults = table.withTable() {
          htable =>
            val scanner = htable.getScanner(scan)
            try {
              for (result <- scanner; if (result != null)) yield {
                val rr = table.buildRow(result)
                cacheHandler(rr)
                handler(rr)
              }
            } finally {
              cacheComplete()
              scanner.close()
            }
        }

        runResults
      }
    }
    results2
  }

  /**Similar to the scan method but if your handler returns false, it will stop scanning.
   *
   */
  def scanUntil(handler: (RR) => Boolean, maxVersions: Int = 1, cacheBlocks: Boolean = true, cacheSize: Int = 100) {
    table.withTable() {
      htable =>
        val scan = makeScanner(maxVersions, cacheBlocks, cacheSize)

        val scanner = htable.getScanner(scan)
        val rows = scanner.iterator map { result => table.buildRow(result) }

        for (row <- rows takeWhile { r => ! handler(r) }) {}
        scanner.close()
    }
  }

}

object Query {
  def p(depth: Int = 1, msg: Any) {
    println(("\t" * depth) + msg)
  }

  def printFilter(depth: Int, f: Filter) {
    p(depth, "Filter All Remaining: " + f.filterAllRemaining())
    p(depth, "Has Filter Row: " + f.hasFilterRow)
    p(depth, "To String: " + f.toString)
    if (f.isInstanceOf[FilterList]) {
      val fl = f.asInstanceOf[FilterList]
      p(depth, "Operator: " + fl.getOperator())
      fl.getFilters.foreach(sf => printFilter(depth + 1, sf))
    }
  }

}
