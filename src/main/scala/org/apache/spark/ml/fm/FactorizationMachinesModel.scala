package org.apache.spark.ml.fm

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.ml.Model
import org.apache.spark.ml.linalg.{DenseVector, Vector, VectorUDT, Vectors}
import org.apache.spark.ml.param.shared.{HasFeaturesCol, HasLabelCol, HasPredictionCol}
import org.apache.spark.ml.param.{Param, ParamMap, Params}
import org.apache.spark.ml.util.SchemaUtils
import org.apache.spark.sql.expressions.{UserDefinedFunction, Window}
import org.apache.spark.sql.functions.{col, _}
import org.apache.spark.sql.types.{FloatType, StructType}
import org.apache.spark.sql.{DataFrame, Dataset}

import scala.util.Random

/**
  *
  */
private[fm] trait FactorizationMachinesModelParams
  extends Params
    with HasFeaturesCol
    with HasPredictionCol
    with HasLabelCol
{
  val sampleIdCol = new Param[String](this, "sampleIdCol", "Column name for sample ID")
  def getSampleIdCol: String = $(sampleIdCol)

  val minLabel = new Param[Double](this, "minLabel", "Minimum label value")
  def getMinLabel: Double = $(minLabel)

  val maxLabel = new Param[Double](this, "maxLabel", "Maximum label value")
  def getMaxLabel: Double = $(maxLabel)
}

/**
  *
  * @param uid
  * @param dimFactorization
  * @param globalBias
  * @param dimensionStrength
  * @param factorizedInteraction
  */
class FactorizationMachinesModel(override val uid: String,
                                 val dimFactorization: Int,
                                 val globalBias: Double,
                                 val dimensionStrength: Dataset[Strength],
                                 val factorizedInteraction: Dataset[FactorizedInteraction])
  extends Model[FactorizationMachinesModel] with FactorizationMachinesModelParams {

  def setMinLabel(value: Double): this.type = set(minLabel, value)

  def setMaxLabel(value: Double): this.type = set(maxLabel, value)

  setDefault(
    sampleIdCol -> "sampleId",
    featuresCol -> "features",
    predictionCol -> "prediction",
    labelCol -> "label",
    minLabel -> 0.0,
    maxLabel -> 1.0
  )

  override def copy(extra: ParamMap): FactorizationMachinesModel = {
    val copied = new FactorizationMachinesModel(uid, dimFactorization, globalBias, dimensionStrength, factorizedInteraction)
    copyValues(copied, extra).setParent(parent)
  }

  @org.apache.spark.annotation.Since("2.0.0")
  override def transform(dataset: Dataset[_]): DataFrame = {
    transformSchema(dataset.schema)

    import dataset.sparkSession.implicits._

    val dfSampleIndexed = FactorizationMachinesModel.addSampleId(dataset, $(sampleIdCol)).cache()

    val predicted = predict(dfSampleIndexed)

    dfSampleIndexed.as("si")
      .join(
        predicted.as("p"),
        $"si.sampleId" === $"p.sampleId",
        "left_outer"
      )
      .drop($"si.sampleId")
      .drop($"p.sampleId")
      .na.fill(globalBias, Seq($(predictionCol)))
  }


  private def predict(dfSampleIndexed: DataFrame): DataFrame = {
    val spark = dfSampleIndexed.sparkSession

    import spark.implicits._

    val bcW0 = spark.sparkContext.broadcast(globalBias)

    dfSampleIndexed
      .select(
        dfSampleIndexed("sampleId"),
        explode(FactorizationMachinesModel.udfVecToMap(dfSampleIndexed($(featuresCol)))) as Seq("featureId", "featureValue")
      )
      .as("exploded")
      .join(
        dimensionStrength as "ds",
        $"exploded.featureId" === $"ds.id",
        "inner"
      )
      .join(
        factorizedInteraction as "fi",
        $"exploded.featureId" === $"fi.id",
        "inner"
      )
      .select(
        col("sampleId"),
        $"ds.strength" * col("featureValue") as "wixi",
        FactorizationMachinesModel.udfVecMultipleByScalar($"fi.vec", col("featureValue")) as "vfxi",
        FactorizationMachinesModel.vi2xi2($"fi.vec", col("featureValue")) as "vi2xi2"
      )
      .groupBy(col("sampleId"))
      .agg(
        sum(col("wixi")) as "wixiSum",
        (new VectorSum(dimFactorization))(col("vfxi")) as "vfxiSum",
        sum(col("vi2xi2")) as "vi2xi2Sum"
      )
      .select(
        col("sampleId"),
        (FactorizationMachinesModel.sumVx(col("vfxiSum"), col("vi2xi2Sum")) + col("wixiSum") + bcW0.value) as $(predictionCol)
      )
      .select(
        col("sampleId"),
        least(greatest(col($(predictionCol)), lit(getMinLabel)), lit(getMaxLabel)) as $(predictionCol)
      )
  }

  def calcLossGrad(dfSampleIndexed: DataFrame, initialSd: Double): DataFrame = {
    require(initialSd > 0.0, "initSd (initial Standard Deviation) must be > 0.0")

    val spark = dfSampleIndexed.sparkSession

    import spark.implicits._

    val bcW0 = spark.sparkContext.broadcast(globalBias)

    val udfInitVec = udf {
      () => Vectors.dense((0 until dimFactorization).map { _ => Random.nextGaussian() * initialSd } .toArray)
    }

    dfSampleIndexed
      .select(
        col($(labelCol)),
        col("sampleId"),
        explode(FactorizationMachinesModel.udfVecToMap(col($(featuresCol)))) as Seq("featureId", "featureValue")
      )
      .as("exploded")
      .join(
        dimensionStrength as "ds",
        $"exploded.featureId" === $"ds.id",
        "left_outer"
      )
      .join(
        factorizedInteraction as "fi",
        $"exploded.featureId" === $"fi.id",
        "left_outer"
      )
      .select(
        col($(labelCol)),
        col("sampleId"),
        col("featureId"),
        col("featureValue"),
        coalesce(col("strength"), randn() * initialSd) as "strength",
        coalesce(col("vec"), udfInitVec()) as "factorizedInteraction"
      )
      .select(
        col($(labelCol)),
        col("sampleId"),
        col("featureId"),
        col("featureValue") as "xi",
        col("strength") * col("featureValue") as "wixi",
        FactorizationMachinesModel.udfVecMultipleByScalar(col("factorizedInteraction"), col("featureValue")) as "vfxi",
        FactorizationMachinesModel.vi2xi2(col("factorizedInteraction"), col("featureValue")) as "vi2xi2"
      )
      .select(
        col($(labelCol)),
        col("sampleId"),
        col("featureId"),
        col("xi"),
        col("wixi"),
        col("vfxi"),
        col("vi2xi2"),
        FactorizationMachinesModel.udfVecMultipleByScalar(col("vfxi"), col("xi")) as "vfxi2",
        (new VectorSum(dimFactorization))(col("vfxi")).over(Window.partitionBy(col("sampleId"))) as "vfxiSum"
      )
      .select(
        col($(labelCol)),
        col("sampleId"),
        col("featureId"),
        col("wixi"),
        col("vfxi"),
        col("vi2xi2"),
        col("xi") as "deltaWi",
        FactorizationMachinesModel.udfVecMinusVec(
          FactorizationMachinesModel.udfVecMultipleByScalar(col("vfxiSum"), col("xi")),
          col("vfxi2")
        ) as "deltaVi",
        col("vfxiSum")
      )
      .select(
        col($(labelCol)),
        col("sampleId"),
        col("featureId"),
        sum(col("wixi")).over(Window.partitionBy(col("sampleId"))) as "wixiSum",
        sum(col("vi2xi2")).over(Window.partitionBy(col("sampleId"))) as "vi2xi2Sum",
        col("vfxiSum"),
        col("deltaWi"),
        col("deltaVi")
      )
      .select(
        col($(labelCol)),
        col("sampleId"),
        col("featureId"),
        (FactorizationMachinesModel.sumVx(col("vfxiSum"), col("vi2xi2Sum")) + col("wixiSum") + bcW0.value) as $(predictionCol),
        col("deltaWi"),
        col("deltaVi")
      )
      .select(
        col($(labelCol)),
        col("sampleId"),
        col("featureId"),
        col($(predictionCol)),
        pow(col($(predictionCol)) - col($(labelCol)), 2.0) as "loss",
        col("deltaWi"),
        col("deltaVi")
      )
  }

  @DeveloperApi
  override def transformSchema(schema: StructType): StructType = {
    SchemaUtils.checkColumnType(schema, $(featuresCol), new VectorUDT)
    SchemaUtils.appendColumn(schema, $(predictionCol), FloatType)
  }
}

object FactorizationMachinesModel {
  val udfVecToMap: UserDefinedFunction = udf {
    (vec: Vector) => {
      val m = scala.collection.mutable.Map[Int, Double]()
      vec.foreachActive { (i, value) => m += (i -> value) }
      m
    }
  }

  val udfVecMultipleByScalar: UserDefinedFunction = udf {
    (v: Vector, d: Double) => Vectors.fromBreeze(v.asBreeze * d)
  }

  val vi2xi2: UserDefinedFunction = udf {
    (vi: Vector, xi: Double) => vi.toArray.map { vif => vif * vif }.sum * xi * xi
  }

  val sumVx: UserDefinedFunction = udf {
    (vfxiSum: Vector, vi2xi2Sum: Double) => 0.5 * (vfxiSum.toArray.map { vf => vf * vf }.sum - vi2xi2Sum)
  }

  val udfVecMinusVec: UserDefinedFunction = udf {
    (v1: Vector, v2: Vector) => Vectors.fromBreeze(v1.asBreeze - v2.asBreeze)
  }

  def addSampleId(dataset: Dataset[_], columnName: String): DataFrame = dataset
    .select(
      dataset("*"),
      monotonically_increasing_id() as columnName
    )
}

/**
  * The strength of the i-th feature
  *
  * @param id feature ID
  * @param strength strength (w_i)
  */
case class Strength(id: Int, strength: Double)

/**
  * Factorized interaction between i-th and j-th features
  *
  * @param id feature ID
  * @param vec factorized interaction as vector (v_i) with length k
  */
case class FactorizedInteraction(id: Int, vec: DenseVector)
