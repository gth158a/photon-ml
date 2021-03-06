/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.data.avro

import java.lang.{Double => JDouble}
import java.util.{Map => JMap}

import scala.collection.JavaConversions._
import scala.collection.Map
import scala.io.Source
import scala.util.Try

import breeze.linalg.{DenseVector, SparseVector, Vector}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import com.linkedin.photon.avro.generated.FeatureSummarizationResultAvro
import com.linkedin.photon.ml.avro.generated.{BayesianLinearModelAvro, LatentFactorAvro}
import com.linkedin.photon.ml.estimators.GameParams
import com.linkedin.photon.ml.model._
import com.linkedin.photon.ml.optimization.game.GLMOptimizationConfiguration
import com.linkedin.photon.ml.stat.BasicStatisticalSummary
import com.linkedin.photon.ml.supervised.model.GeneralizedLinearModel
import com.linkedin.photon.ml.util.MathUtils.isAlmostZero
import com.linkedin.photon.ml.util._
import com.linkedin.photon.ml.{Constants, TaskType}

/**
 * Some basic functions to read/write GAME models from/to HDFS.
 *
 * The current implementation assumes the models are stored using an Avro format.
 * The main challenge in saving/loading GAME models is that the number of random effect submodels can be
 * arbitrarily large. That's why e.g. "numberOfOutputFilesForRandomEffectModel" is needed.
 *
 * TODO: this object needs additional documentation
 * TODO: we might want to extract the various Path we setup to a method called by both save and load,
 * (to avoid bugs where save would use different Paths from load)
 *
 * TODO: Change the scope of all functions to [[com.linkedin.photon.ml.avro]] after Avro related
 * classes/functions are decoupled from the rest of code
 *
 * TODO: separate what's Avro and what's not, and locate appropriately: most of this should go into photon.ml.models
 */
object ModelProcessingUtils {

  /**
   * Save a GAME model to HDFS.
   *
   * @note GAME models can grow very large, because they can accommodate an unlimited number of random effect submodels.
   *       Therefore extra care is required when saving the random effects submodels.
   *
   * @param gameModel The GAME model to save
   * @param featureShardIdToFeatureMapLoader The maps of feature to shard ids
   * @param outputDir The directory in HDFS where to save the model
   * @param params The parameters that were setup to run this model
   * @param sparkContext The Spark context
   */
  protected[ml] def saveGameModelsToHDFS(
      gameModel: GAMEModel,
      featureShardIdToFeatureMapLoader: Map[String, IndexMapLoader],
      outputDir: String,
      params: GameParams,
      sparkContext: SparkContext): Unit = {

    val hadoopConfiguration = sparkContext.hadoopConfiguration

    saveGameModelMetadataToHDFS(sparkContext, params, outputDir)

    gameModel.toMap.foreach { case (name, model) =>
      model match {
        case fixedEffectModel: FixedEffectModel =>
          val featureShardId = fixedEffectModel.featureShardId
          val fixedEffectModelOutputDir = new Path(outputDir, s"${AvroConstants.FIXED_EFFECT}/$name").toString
          Utils.createHDFSDir(fixedEffectModelOutputDir, hadoopConfiguration)

          //Write the model ID info
          val modelIdInfoPath = new Path(fixedEffectModelOutputDir, AvroConstants.ID_INFO)
          val id = Array(featureShardId)
          IOUtils.writeStringsToHDFS(id.iterator, modelIdInfoPath, hadoopConfiguration, forceOverwrite = false)

          //Write the coefficients
          val coefficientsOutputDir = new Path(fixedEffectModelOutputDir, AvroConstants.COEFFICIENTS).toString
          Utils.createHDFSDir(coefficientsOutputDir, hadoopConfiguration)
          val indexMap = featureShardIdToFeatureMapLoader(featureShardId).indexMapForDriver()
          val model = fixedEffectModel.model
          saveModelToHDFS(model, indexMap, coefficientsOutputDir, sparkContext)

        case randomEffectModel: RandomEffectModel =>
          val randomEffectType = randomEffectModel.randomEffectType
          val featureShardId = randomEffectModel.featureShardId
          val randomEffectModelOutputDir = new Path(outputDir, s"${AvroConstants.RANDOM_EFFECT}/$name")

          //Write the model ID info
          val modelIdInfoPath = new Path(randomEffectModelOutputDir, AvroConstants.ID_INFO)
          val ids = Array(randomEffectType, featureShardId)
          IOUtils.writeStringsToHDFS(ids.iterator, modelIdInfoPath, hadoopConfiguration, forceOverwrite = false)

          val indexMapLoader = featureShardIdToFeatureMapLoader(featureShardId)
          saveRandomEffectModelToHDFS(
            randomEffectModel,
            indexMapLoader,
            randomEffectModelOutputDir,
            params.numberOfOutputFilesForRandomEffectModel,
            hadoopConfiguration)
      }
    }
  }

  /**
   * Load a GAME model from HDFS.
   *
   * This method can be called with or without a feature index. If a feature index is not provided, one is created
   * by scanning the loaded models. In that case, the indexes ranges are [0..numNonZeroFeatures], even if the feature
   * index used before saving the model was sparse. In other words, when no feature index is provided for the load,
   * the feature index before the save might not be the same as after the load (it will be more "compact" after the
   * load, using only contiguous indexes).
   *
   * @param featureShardIdToIndexMapLoader An optional feature index loader
   * @param modelsDir The directory on HDFS where the models are stored
   * @param sc The Spark context
   * @return The GAME model and feature index
   */
  protected[ml] def loadGameModelFromHDFS(
      featureShardIdToIndexMapLoader: Option[Map[String, IndexMapLoader]],
      modelsDir: String,
      sc: SparkContext): (GAMEModel, Map[String, IndexMapLoader]) = {

    val configuration = sc.hadoopConfiguration
    val inputDirAsPath = new Path(modelsDir)
    val fs = inputDirAsPath.getFileSystem(configuration)
    val modelType = loadGameModelMetadataFromHDFS(sc, modelsDir).taskType

    // Load the fixed effect model(s)
    val fixedEffectModelInputDir = new Path(modelsDir, AvroConstants.FIXED_EFFECT)
    val fixedEffectModels = if (fs.exists(fixedEffectModelInputDir)) {
      fs.listStatus(fixedEffectModelInputDir).map { fileStatus =>
        val innerPath = fileStatus.getPath
        val name = innerPath.getName

        // Load the model ID info
        val idInfoPath = new Path(innerPath, AvroConstants.ID_INFO)
        val Array(featureShardId) = IOUtils.readStringsFromHDFS(idInfoPath, configuration).toArray
        require(featureShardId != null && !featureShardId.isEmpty)

        // Load the coefficients
        val indexMapLoaders = featureShardIdToIndexMapLoader.flatMap(_.get(featureShardId))
        val featureNameAndTermToIndexMap = indexMapLoaders.map(_.indexMapForDriver())
        val modelPath = new Path(innerPath, AvroConstants.COEFFICIENTS)
        val (glm, featureIndexLoader) = loadGLMFromHDFS(modelPath.toString, featureNameAndTermToIndexMap, sc)

        (name, featureIndexLoader, new FixedEffectModel(sc.broadcast(glm), featureShardId))
      }
    } else {
      Array[(String, IndexMapLoader, FixedEffectModel)]()
    }

    // Load the random effect models
    val randomEffectModelInputDir = new Path(modelsDir, AvroConstants.RANDOM_EFFECT)
    val randomEffectModels = if (fs.exists(randomEffectModelInputDir)) {

      fs.listStatus(randomEffectModelInputDir).map { innerFileStatus =>

        val innerPath = innerFileStatus.getPath
        val name = innerPath.getName

        // Load the model ID info
        val idInfoPath = new Path(innerPath, AvroConstants.ID_INFO)
        val Array(randomEffectType, featureShardId) = IOUtils.readStringsFromHDFS(idInfoPath, configuration).toArray

        // Load the models
        val featureMapLoader = featureShardIdToIndexMapLoader.flatMap(_.get(featureShardId))
        val modelsRDDInputPath = new Path(innerPath, AvroConstants.COEFFICIENTS)
        val (modelsRDD, featureIndexLoader) =
          loadModelsRDDFromHDFS(modelsRDDInputPath.toString, featureMapLoader, sc)

        (name, featureIndexLoader, new RandomEffectModel(modelsRDD, randomEffectType, featureShardId))
      }
    } else {
      Array[(String, IndexMapLoader, RandomEffectModel)]()
    }

    val datumScoringModels: Array[(String, IndexMapLoader, DatumScoringModel)] = fixedEffectModels ++ randomEffectModels
    val datumScoringModelNames = datumScoringModels.map(_._1)
    val numDatumScoringModels = datumScoringModels.length

    require(numDatumScoringModels > 0, s"No models could be loaded from given path: $modelsDir")
    require(
      numDatumScoringModels == datumScoringModelNames.toSet.size,
      s"Duplicated model names found:\n${datumScoringModelNames.mkString("\n")}")

    // Need to massage the data structure a bit so that we can return the feature index loader(s) and
    // the GAME model separately
    val (models, featureIndexLoaders) = datumScoringModels
      .map {
        case ((name, featureIndexLoader, model: FixedEffectModel)) =>
          ((name, model), (model.featureShardId, featureIndexLoader))

        case ((name, featureIndexLoader, model: RandomEffectModel)) =>
          ((name, model), (model.featureShardId, featureIndexLoader))

        case ((name, _, _)) =>
          throw new RuntimeException(s"Unknown model type for: $name")
      }
      .unzip
    val gameModel = new GAMEModel(models.toMap)

    require(
      modelType == TaskType.NONE || gameModel.modelType == modelType,
      s"GAME model type ${gameModel.modelType} does not match type $modelType listed in metadata")

    (gameModel, featureIndexLoaders.toMap)
  }

  /**
   * Given a GLM and a feature index (loader), return an Array[(String, Double)] of pairs (name, value) for all
   * the features used in that model.
   *
   * Since we use both dense and sparse vectors, we need a switch to retrieve an iterator on the non-zeros only.
   *
   * @param glm The GLM from which to extract feature (name, value) pairs.
   * @param featureIndex The feature index to decode this GLM
   * @return An array of pairs (name, value) for all the active (non-zero) features in the glm
   */
  protected[ml] def extractGLMFeatures(glm: GeneralizedLinearModel, featureIndex: IndexMap): Array[(String, Double)] = {

    val coefficients: Iterator[(Int, Double)] = glm.coefficients.means match { // (index, value)
      case (vector: DenseVector[Double]) => vector.iterator
      case (vector: SparseVector[Double]) => vector.activeIterator // activeIterator to iterate over the non-zeros
    }

    coefficients // flatMap filters out None values that can result from the case statement just above
      .flatMap { case (index, value) => featureIndex.getFeatureName(index).map((_, value)) }
      .filter { case (_, value) => ! isAlmostZero(value) } // we want only non-zeros
      .toArray // returns: Array[(feature name: String, feature value: Double)]
  }

  /**
   * For a given GAME model and feature indexes, returns names and values for all the non-zero features in the
   * model.
   *
   * Since GAME models can be very large, the processing is distributed. In the case of random effects in
   * particular, "mapPartitions" will serialize and send the content of the "iter => ..." lambda to the executors
   * in the Spark cluster. If we didn't do that, "indexMapForRDD" would be called for each single datum, which
   * would be too slow. In the case of fixed effect models (usually there is only one), we assume that the fixed
   * effect model has a feature index that can fit within a single executor.
   *
   * On HDFS the dirs/files data structures looks like, e.g.:
   *
   *   hdfs://hostname:port/tmp/GAMELaserModelTest/GAMEModel/fixed-effect/fixed/coefficients/part-00000.avro
   *   hdfs://hostname:port/tmp/GAMELaserModelTest/GAMEModel/fixed-effect/fixed/id-info
   *   hdfs://hostname:port/tmp/GAMELaserModelTest/GAMEModel/random-effect/RE1/coefficients/_SUCCESS
   *   hdfs://hostname:port/tmp/GAMELaserModelTest/GAMEModel/random-effect/RE1/coefficients/part-00000.avro
   *   hdfs://hostname:port/tmp/GAMELaserModelTest/GAMEModel/random-effect/RE1/coefficients/part-00001.avro
   *   hdfs://hostname:port/tmp/GAMELaserModelTest/GAMEModel/random-effect/RE1/id-info
   *   hdfs://hostname:port/tmp/GAMELaserModelTest/GAMEModel/random-effect/RE2/coefficients/_SUCCESS
   *   hdfs://hostname:port/tmp/GAMELaserModelTest/GAMEModel/random-effect/RE2/coefficients/part-00000.avro
   *   hdfs://hostname:port/tmp/GAMELaserModelTest/GAMEModel/random-effect/RE2/coefficients/part-00001.avro
   *   hdfs://hostname:port/tmp/GAMELaserModelTest/GAMEModel/random-effect/RE2/id-info
   *
   * @param gameModel The GAME model to extract all features from
   * @param featureIndexLoaders The feature index loaders ; each feature space has a separate, named feature index
   * @return All the (feature name, feature value) pairs of that GAME model.
   *         The first Map's keys are "(fixed|random, sectionName)". Then the RDD String keys are like
   *         "random effect id 123". Finally, each user has an element in the RDD element that contains
   *         an array of pairs (feature name, feature value)
   */
  protected[ml] def extractGameModelFeatures(
      sc: SparkContext,
      gameModel: GAMEModel,
      featureIndexLoaders: Map[String, IndexMapLoader]): Map[(String, String), RDD[(String, Array[(String, Double)])]] = {

    gameModel.toMap.map {

      case (fixedEffect: String, model: FixedEffectModel) =>
        val featureIndex = featureIndexLoaders(model.featureShardId).indexMapForRDD()
        ((AvroConstants.FIXED_EFFECT, fixedEffect),
          sc.parallelize(Array((fixedEffect, extractGLMFeatures(model.model, featureIndex)))))

      case (randomEffect: String, model: RandomEffectModel) =>
        val featureShardId = model.featureShardId // each random effect has its own feature space (= shard id)
        ((AvroConstants.RANDOM_EFFECT, randomEffect), model.modelsRDD.mapPartitions { iter => // Partition has many random effects
          val featureIndexes = featureIndexLoaders(featureShardId).indexMapForRDD()
          iter.map { case (subModelId, subModel) => // e.g. subModelId = "random effect id 123"
            (subModelId, extractGLMFeatures(subModel, featureIndexes)) }
        })

      case (modelType, _) => throw new RuntimeException(s"Unknown model type: $modelType")
    }
  }

  /**
   *
   * @param randomEffectModel
   * @param indexMapLoader
   * @param randomEffectModelOutputDir
   * @param numberOfOutputFilesForRandomEffectModel
   * @param configuration
   */
  private def saveRandomEffectModelToHDFS(
      randomEffectModel: RandomEffectModel,
      indexMapLoader: IndexMapLoader,
      randomEffectModelOutputDir: Path,
      numberOfOutputFilesForRandomEffectModel: Int,
      configuration: Configuration): Unit = {

    Utils.createHDFSDir(randomEffectModelOutputDir.toString, configuration)

    //Write the coefficientsRDD
    val coefficientsRDDOutputDir = new Path(randomEffectModelOutputDir, AvroConstants.COEFFICIENTS).toString
    val modelsRDD = if (numberOfOutputFilesForRandomEffectModel > 0){
        // Control the number of output files by re-partitioning the RDD.
        randomEffectModel.modelsRDD.coalesce(numberOfOutputFilesForRandomEffectModel)
      } else {
        randomEffectModel.modelsRDD
      }

    saveModelsRDDToHDFS(modelsRDD, indexMapLoader, coefficientsRDDOutputDir)
  }

  /**
   *
   * @param model
   * @param featureMap
   * @param outputDir
   * @param sparkContext
   */
  private def saveModelToHDFS(
      model: GeneralizedLinearModel,
      featureMap: IndexMap,
      outputDir: String,
      sparkContext: SparkContext): Unit = {

    val bayesianLinearModelAvro = AvroUtils.convertGLMModelToBayesianLinearModelAvro(
      model,
      AvroConstants.FIXED_EFFECT,
      featureMap)
    val modelOutputPath = new Path(outputDir, AvroConstants.DEFAULT_AVRO_FILE_NAME).toString

    AvroUtils.saveAsSingleAvro(
      sparkContext,
      Seq(bayesianLinearModelAvro),
      modelOutputPath,
      BayesianLinearModelAvro.getClassSchema.toString)
  }

  /**
   *
   * TODO: Currently only the means of the coefficients are loaded, the variances are discarded
   *
   * @param inputDir
   * @param featureMap
   * @param sc
   * @return
   */
  private def loadGLMFromHDFS(
      inputDir: String,
      featureMap: Option[IndexMap],
      sc: SparkContext): (GeneralizedLinearModel, IndexMapLoader) = {

    val coefficientsPath = new Path(inputDir, AvroConstants.DEFAULT_AVRO_FILE_NAME).toString
    // next line is log reg
    val linearModelAvroSchema = BayesianLinearModelAvro.getClassSchema.toString
    // next line is lin reg - we lost the log reg information
    val linearModelAvro = AvroUtils.readFromSingleAvro[BayesianLinearModelAvro](sc, coefficientsPath,
      linearModelAvroSchema).head

    val featureIndex = featureMap.getOrElse(AvroUtils.makeFeatureIndexForModel(linearModelAvro))

    // We wrap the feature index in a loader to be more consistent with loadModelsRDDFromHDFS
    (AvroUtils.convertBayesianLinearModelAvroToGLM(linearModelAvro, featureIndex),
      DefaultIndexMapLoader(sc, featureIndex))
  }

  /**
   *
   * @param modelsRDD
   * @param featureMapLoader
   * @param outputDir
   */
  private def saveModelsRDDToHDFS(
      modelsRDD: RDD[(String, GeneralizedLinearModel)],
      featureMapLoader: IndexMapLoader,
      outputDir: String): Unit = {

    val linearModelAvro = modelsRDD.mapPartitions { iter =>
      val featureMap = featureMapLoader.indexMapForRDD()
      iter.map { case (modelId, model) =>
        AvroUtils.convertGLMModelToBayesianLinearModelAvro(model, modelId, featureMap)
      }
    }

    AvroUtils.saveAsAvro(linearModelAvro, outputDir, BayesianLinearModelAvro.getClassSchema.toString)
  }

  /**
   * TODO: Currently only the means of the coefficients are loaded, the variances are discarded
   *
   * @param coefficientsRDDInputDir
   * @param featureMapLoader
   * @param sc
   * @return
   */
  private def loadModelsRDDFromHDFS(
      coefficientsRDDInputDir: String,
      featureMapLoader: Option[IndexMapLoader],
      sc: SparkContext): (RDD[(String, GeneralizedLinearModel)], IndexMapLoader) = {

    val modelAvros = AvroUtils.readAvroFilesInDir[BayesianLinearModelAvro](
      sc,
      coefficientsRDDInputDir,
      minNumPartitions = sc.defaultParallelism)

    val loader = featureMapLoader.getOrElse(AvroUtils.makeFeatureIndexForModel(sc, modelAvros))

    (modelAvros.mapPartitions { iter =>
      val featureMap = loader.indexMapForRDD()
      iter.map { modelAvro =>
        val modelId = modelAvro.getModelId.toString
        val glm = AvroUtils.convertBayesianLinearModelAvroToGLM(modelAvro, featureMap)

        (modelId, glm)
      }
    }, loader)
  }

  /**
   * Save the matrix factorization model of type [[MatrixFactorizationModel]] to HDFS as Avro files.
   *
   * @param matrixFactorizationModel The given matrix factorization model
   * @param outputDir The HDFS output directory for the matrix factorization model
   * @param numOutputFiles Number of output files to generate for row/column latent factors of the matrix
   *                       factorization model
   * @param sparkContext The Spark context
   */
  protected[ml] def saveMatrixFactorizationModelToHDFS(
      matrixFactorizationModel: MatrixFactorizationModel,
      outputDir: String,
      numOutputFiles: Int,
      sparkContext: SparkContext): Unit = {

    val rowLatentFactors = matrixFactorizationModel.rowLatentFactors
    val rowEffectType = matrixFactorizationModel.rowEffectType
    val rowLatentFactorsOutputDir = new Path(outputDir, rowEffectType).toString
    val rowLatentFactorsAvro = rowLatentFactors.coalesce(numOutputFiles).map { case (rowId, latentFactor) =>
      AvroUtils.convertLatentFactorToLatentFactorAvro(rowId, latentFactor)
    }
    AvroUtils.saveAsAvro(rowLatentFactorsAvro, rowLatentFactorsOutputDir, LatentFactorAvro.getClassSchema.toString)

    val colLatentFactors = matrixFactorizationModel.colLatentFactors
    val colEffectType = matrixFactorizationModel.colEffectType
    val colLatentFactorsOutputDir = new Path(outputDir, colEffectType).toString
    val colLatentFactorsAvro = colLatentFactors.coalesce(numOutputFiles).map { case (colId, latentFactor) =>
      AvroUtils.convertLatentFactorToLatentFactorAvro(colId, latentFactor)
    }
    AvroUtils.saveAsAvro(colLatentFactorsAvro, colLatentFactorsOutputDir, LatentFactorAvro.getClassSchema.toString)
  }

  private def loadLatentFactorsFromHDFS(inputDir: String, sparkContext: SparkContext): RDD[(String, Vector[Double])] = {
    val minNumPartitions = sparkContext.defaultParallelism
    val modelAvros = AvroUtils.readAvroFilesInDir[LatentFactorAvro](sparkContext, inputDir, minNumPartitions)
    modelAvros.map(AvroUtils.convertLatentFactorAvroToLatentFactor)
  }

  /**
   * Load the matrix factorization model of type [[MatrixFactorizationModel]] from the Avro files on HDFS.
   *
   * @param inputDir The input directory of the Avro files on HDFS
   * @param rowEffectType What each row of the matrix corresponds to, e.g., memberId or itemId
   * @param colEffectType What each column of the matrix corresponds to, e.g., memberId or itemId
   * @param sparkContext The Spark context
   * @return The loaded matrix factorization model of type [[MatrixFactorizationModel]]
   */
  protected[ml] def loadMatrixFactorizationModelFromHDFS(
      inputDir: String,
      rowEffectType: String,
      colEffectType: String,
      sparkContext: SparkContext): MatrixFactorizationModel = {

    val configuration = sparkContext.hadoopConfiguration
    val inputDirAsPath = new Path(inputDir)
    val fs = inputDirAsPath.getFileSystem(configuration)
    assert(fs.exists(inputDirAsPath),
      s"Specified input directory $inputDir for matrix factorization model is not found")
    val rowLatentFactorsPath = new Path(inputDir, rowEffectType)
    assert(fs.exists(rowLatentFactorsPath),
      s"Specified input directory $rowLatentFactorsPath for row latent factors is not found")
    val rowLatentFactors = loadLatentFactorsFromHDFS(rowLatentFactorsPath.toString, sparkContext)
    val colLatentFactorsPath = new Path(inputDir, colEffectType)
    assert(fs.exists(colLatentFactorsPath),
      s"Specified input directory $colLatentFactorsPath for column latent factors is not found")
    val colLatentFactors = loadLatentFactorsFromHDFS(colLatentFactorsPath.toString, sparkContext)
    new MatrixFactorizationModel(rowEffectType, colEffectType, rowLatentFactors, colLatentFactors)
  }

  /**
   * Save model metadata to a JSON file.
   *
   * @param outputDir The HDFS directory that will contain the metadata file
   * @param params The model parameters, from which metadata is extracted
   * @param sparkContext The Spark context
   */
  def saveGameModelMetadataToHDFS(
      sparkContext: SparkContext,
      params: GameParams,
      outputDir: String,
      metadataFilename: String = "model-metadata.json"): Unit = {

    // 2 ancillaries that decompose the problem into more manageable tasks
    def writeConfigToJson(configs: Map[String, GLMOptimizationConfiguration]) =
      configs
        .map { case (cfgName, cfg) => s"""{ "name": "$cfgName", "configuration": ${cfg.toJson} }""" }
        .mkString(",")

    def writeConfigsArrayToJson(which: String, configsArray: Array[Predef.Map[String, GLMOptimizationConfiguration]]) =
      s"""
         |{ "configurations": "$which",
         |  "values": [
         |    ${configsArray.map(cfgs => writeConfigToJson(cfgs)).mkString(",")}
         |  ]
         |}
       """.stripMargin

    val feConfigurations = writeConfigsArrayToJson("fixed-effect", params.fixedEffectOptimizationConfigurations)
    val reConfigurations = writeConfigsArrayToJson("random-effect", params.randomEffectOptimizationConfigurations)

    IOUtils.toHDFSFile(sparkContext, outputDir + "/" + metadataFilename) {
      writer => writer.println(
        s"""
           |{ "modelType": "${params.taskType}",
           |  "modelName": "${params.applicationName}",
           |  "fixedEffectOptimizationConfigurations": $feConfigurations,
           |  "randomEffectOptimizationConfigurations": $reConfigurations
           |}
         """.stripMargin)
    }
  }

  /**
   * Write basic feature statistics in Avro format.
   *
   * @param sc Spark context
   * @param summary The summary of the features
   * @param outputDir Output directory
   */
  def writeBasicStatistics(
    sc: SparkContext,
    summary: BasicStatisticalSummary, outputDir: String,
    keyToIdMap: IndexMap): Unit = {

    case class BasicSummaryItems(
      max: Double,
      min: Double,
      mean: Double,
      normL1: Double,
      normL2: Double,
      numNonzeros: Double,
      variance: Double)

    def featureStringToTuple(str: String): (String, String) = {
      val splits = str.split(Constants.DELIMITER)
      if (splits.length == 2) {
        (splits(0), splits(1))
      } else {
        (splits(0), "")
      }
    }

    val featureTuples = keyToIdMap
      .toArray
      .sortBy[Int] { case (_, id) => id }
      .map { case (key, _) => featureStringToTuple(key) }

    val summaryList = List(
      summary.max.toArray,
      summary.min.toArray,
      summary.mean.toArray,
      summary.normL1.toArray,
      summary.normL2.toArray,
      summary.numNonzeros.toArray,
      summary.variance.toArray)
      .transpose
      .map {
        case List(max, min, mean, normL1, normL2, numNonZeros, variance) =>
          BasicSummaryItems(max, min, mean, normL1, normL2, numNonZeros, variance)
      }

    val outputAvro = featureTuples
      .zip(summaryList)
      .map {
        case ((name, term), items) =>
          val jMap: JMap[CharSequence, JDouble] = mapAsJavaMap(Map(
            "max" -> items.max,
            "min" -> items.min,
            "mean" -> items.mean,
            "normL1" -> items.normL1,
            "normL2" -> items.normL2,
            "numNonzeros" -> items.numNonzeros,
            "variance" -> items.variance))

          FeatureSummarizationResultAvro.newBuilder()
            .setFeatureName(name)
            .setFeatureTerm(term)
            .setMetrics(jMap)
            .build()
      }

    val outputFile = new Path(outputDir, AvroConstants.DEFAULT_AVRO_FILE_NAME).toString

    AvroUtils.saveAsSingleAvro(
      sc,
      outputAvro,
      outputFile,
      FeatureSummarizationResultAvro.getClassSchema.toString,
      forceOverwrite = true)
  }

  /**
   * Load model metadata from JSON file.
   *
   * @note for now, we just output model type.
   * TODO: load (and save) more metadata, and return an updated GAMEParams
   *
   * @note If using the builtin Scala JSON parser, watch out, it's not thread safe!
   * @note If there is no metadata file (old models trained before the metadata were introduced),
   *       we assume that the type of GAMEModel is a linear model (each subModel contains its own type)
   *
   * @param inputDir The HDFS directory where the metadata file is located
   * @param sparkContext The Spark context
   * @return Either a new Param object, or Failure if a metadata file was not found, or it did not contain "modelType"
   */
  def loadGameModelMetadataFromHDFS(
      sparkContext: SparkContext,
      inputDir: String,
      metadataFileName: String = "model-metadata.json"): GameParams = {

    val inputPath = new Path(inputDir, metadataFileName)
    val params = new GameParams
    val modelTypeRegularExpression = """"modelType"\s*:\s*"(.+?)"""".r

    val fs = Try(inputPath.getFileSystem(sparkContext.hadoopConfiguration))
    val stream = fs.map(f => f.open(inputPath))
    val fileContents = stream.map(s => Source.fromInputStream(s).getLines.mkString)

    // TODO: Log if we are in a legacy case and don't have metadata

    params.taskType = fileContents.map {
      fc => modelTypeRegularExpression.findFirstMatchIn(fc) match {
        case Some(modelType) => TaskType.withName(modelType.group(1))
        case _ => throw new RuntimeException(s"Couldn't find 'modelType' in metadata file: $inputPath")
      }
    }.getOrElse(TaskType.NONE)

    params
  }
}
