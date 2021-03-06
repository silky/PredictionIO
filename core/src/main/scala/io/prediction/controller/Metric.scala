/** Copyright 2015 TappingStone, Inc.
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *     http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package io.prediction.controller

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

import scala.reflect._
import scala.reflect.runtime.universe._
import grizzled.slf4j.Logger
import Numeric.Implicits._   

/** Base class of a [[Metric]].
  *
  * @tparam EI Evaluation information
  * @tparam Q Query
  * @tparam P Predicted result
  * @tparam A Actual result
  * @tparam R Metric result
  * @group Evaluation
  */
abstract class Metric[EI, Q, P, A, R](implicit rOrder: Ordering[R])
extends Serializable {
  /** Class name of this [[Metric]]. */
  def header: String = this.getClass.getSimpleName

  /** Calculates the result of this [[Metric]]. */
  def calculate(sc: SparkContext, evalDataSet: Seq[(EI, RDD[(Q, P, A)])]): R

  /** Comparison function for R's ordering. */
  def compare(r0: R, r1: R): Int = rOrder.compare(r0, r1)
}

/** Returns the global average of the score returned by the calculate method.
  *
  * @tparam EI Evaluation information
  * @tparam Q Query
  * @tparam P Predicted result
  * @tparam A Actual result
  *
  * @group Evaluation
  */
abstract class AverageMetric[EI, Q, P, A]
    extends Metric[EI, Q, P, A, Double]
    with QPAMetric[Q, P, A, Double] {
  /** Implement this method to return a score that will be used for averaging
    * across all QPA tuples.
    */
  def calculate(q: Q, p: P, a: A): Double

  def calculate(sc: SparkContext, evalDataSet: Seq[(EI, RDD[(Q, P, A)])])
  : Double = {
    val r = evalDataSet
    .par
    .map { case (_, qpaRDD) =>
      qpaRDD.map { case (q, p, a) => calculate(q, p, a) }.stats
    }
    .seq
    .reduce((a, b) => a.merge(b))

    r.mean
  }
}

/** Returns the global average of the non-None score returned by the calculate
  * method.
  *
  * @tparam EI Evaluation information
  * @tparam Q Query
  * @tparam P Predicted result
  * @tparam A Actual result
  *
  * @group Evaluation
  */
abstract class OptionAverageMetric[EI, Q, P, A]
    extends Metric[EI, Q, P, A, Double] 
    with QPAMetric[Q, P, A, Option[Double]] {
  /** Implement this method to return a score that will be used for averaging
    * across all QPA tuples.
    */
  def calculate(q: Q, p: P, a: A): Option[Double]

  def calculate(sc: SparkContext, evalDataSet: Seq[(EI, RDD[(Q, P, A)])])
  : Double = {
    val r = evalDataSet
    .par
    .map { case (_, qpaRDD) =>
      qpaRDD
      .flatMap { case (q, p, a) => calculate(q, p, a).toSeq }
      .stats
    }
    .seq
    .reduce((a, b) => a.merge(b))

    if (r.count == 0) {
      Double.NegativeInfinity
    } else {
      r.mean
    }
  }
}

/** Returns the global stdev of the score returned by the calculate method.
  *
  * This method uses [[org.apache.spark.util.StatCounter]] library, a one pass
  * method is used for calculation.
  *
  * @tparam EI Evaluation information
  * @tparam Q Query
  * @tparam P Predicted result
  * @tparam A Actual result
  *
  * @group Evaluation
  */
abstract class StdevMetric[EI, Q, P, A]
    extends Metric[EI, Q, P, A, Double]
    with QPAMetric[Q, P, A, Double] {
  /** Implement this method to return a score that will be used for calculating
    * the stdev
    * across all QPA tuples.
    */
  def calculate(q: Q, p: P, a: A): Double

  def calculate(sc: SparkContext, evalDataSet: Seq[(EI, RDD[(Q, P, A)])])
  : Double = {
    val r = evalDataSet
    .par
    .map { case (_, qpaRDD) =>
      qpaRDD.map { case (q, p, a) => calculate(q, p, a) }.stats
    }
    .seq
    .reduce((a, b) => a.merge(b))

    r.stdev
  }
}

/** Returns the global stdev of the non-None score returned by the calculate method.
  *
  * This method uses [[org.apache.spark.util.StatCounter]] library, a one pass
  * method is used for calculation.
  *
  * @tparam EI Evaluation information
  * @tparam Q Query
  * @tparam P Predicted result
  * @tparam A Actual result
  *
  * @group Evaluation
  */
abstract class OptionStdevMetric[EI, Q, P, A]
    extends Metric[EI, Q, P, A, Double]
    with QPAMetric[Q, P, A, Option[Double]] {
  /** Implement this method to return a score that will be used for calculating
    * the stdev
    * across all QPA tuples.
    */
  def calculate(q: Q, p: P, a: A): Option[Double]

  def calculate(sc: SparkContext, evalDataSet: Seq[(EI, RDD[(Q, P, A)])])
  : Double = {
    val r = evalDataSet
    .par
    .map { case (_, qpaRDD) =>
      qpaRDD.flatMap { case (q, p, a) => calculate(q, p, a).toSeq }.stats
    }
    .seq
    .reduce((a, b) => a.merge(b))

    r.stdev
  }
}

/** Returns the sum of the score returned by the calculate method. 
  *
  * @tparam EI Evaluation information
  * @tparam Q Query
  * @tparam P Predicted result
  * @tparam A Actual result
  * @tparam R Result, output of the function calculate, must be Numeric
  *
  * @group Evaluation
  */
abstract class SumMetric[EI, Q, P, A, R: ClassTag](implicit num: Numeric[R])
    extends Metric[EI, Q, P, A, R]()(num)
    with QPAMetric[Q, P, A, R] {
  /** Implement this method to return a score that will be used for summing
    * across all QPA tuples.
    */
  def calculate(q: Q, p: P, a: A): R

  def calculate(sc: SparkContext, evalDataSet: Seq[(EI, RDD[(Q, P, A)])])
  : R = {
    val r: Seq[R] = evalDataSet
    .par
    .map { case (_, qpaRDD) => 
      qpaRDD
      .map { case (q, p, a) => calculate(q, p, a) }
      .aggregate[R](num.zero)(_ + _, _ + _) 
    }
    .seq

    r.aggregate[R](num.zero)(_ + _, _ + _)
  }
}


/** Trait for metric which returns a score based on Query, PredictedResult, and ActualResult.
  */
trait QPAMetric[Q, P, A, R] {
  def calculate(q: Q, p: P, a: A): R
}
