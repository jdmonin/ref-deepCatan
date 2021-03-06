package test.catan.logistic;

import java.io.File;
import java.util.ArrayList;
import java.util.Scanner;

import org.apache.commons.io.FileUtils;
import org.deeplearning4j.eval.CatanEvaluation;
import org.deeplearning4j.eval.CatanPlotter;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.transforms.SoftMax;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions.LossFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import data.CatanDataSet;
import data.CatanDataSetIterator;
import data.Normaliser;
import data.PreloadedCatanDataSetIterator;
import util.CatanFeatureMaskingUtil;
import util.CrossValidationUtils;
import util.DataUtils;
import util.NNConfigParser;

/**
 * Cross-validation version of logistic regression baseline for train/test on human data for one task.
 * 
 * @author sorinMD
 *
 */
public class CVCatanLogisticRegression {
	private static Logger log = LoggerFactory.getLogger(CVCatanLogisticRegression.class);
	private static final String METADATA_SEPARATOR = ":";
	private static String PATH;
	private static String DATA_TYPE;
	private static int TASK;
	private static NNConfigParser parser;
	private static boolean NORMALISATION;
	private static boolean softmaxOverOut = true;
	private static boolean selectMax = false;

    public static void main(String[] args) throws Exception {
    	parser = new NNConfigParser(null);//TODO: add config file to resources and accept different ones via the args
    	TASK = parser.getTask();
    	DATA_TYPE = parser.getDataType();
    	DATA_TYPE = DATA_TYPE + "CV";
    	PATH = parser.getDataPath();
    	NORMALISATION = parser.getNormalisation();
    	int FOLDS = 10; //standard 10 fold validation
    	
    	log.info("Load data....");
    	//the file containing the training data
    	File data = new File(PATH + DATA_TYPE + "/alldata-" + TASK + ".txt");
        //read metadata and feed in all the required info
    	Scanner scanner = new Scanner(new File(PATH + DATA_TYPE + "/alldata-" + TASK + "-metadata.txt"));
    	if(!scanner.hasNextLine()){
    		scanner.close();
    		throw new RuntimeException("Metadata not found; Cannot initialise network parameters");
    	}
    	
    	int numInputs = Integer.parseInt(scanner.nextLine().split(METADATA_SEPARATOR)[1]);
        int actInputSize = Integer.parseInt(scanner.nextLine().split(METADATA_SEPARATOR)[1]);
        int maxActions = Integer.parseInt(scanner.nextLine().split(METADATA_SEPARATOR)[1]);
        int nSamples = parser.getNumberOfSamples();
        scanner.close();
        
        //Set specific params
        int epochs = parser.getEpochs();
        int miniBatchSize = parser.getMiniBatchSize(); 
        double labelW = parser.getLabelWeight();
        double metricW = parser.getMetricWeight();
        double learningRate = parser.getLearningRate();
        
        //debugging params (these can be set here)
        int iterations = 1; //iterations over each batch
        long seed = 123;
       
        //full data iterator for data normalisation so we won't have to wait for every fold
        CatanDataSetIterator fullIter = new CatanDataSetIterator(data,nSamples,miniBatchSize,numInputs+1,numInputs+actInputSize+1,true,parser.getMaskHiddenFeatures());
        Normaliser norm = new Normaliser(PATH + DATA_TYPE,parser.getMaskHiddenFeatures());
        if(NORMALISATION){
        	log.info("Check normalisation parameters for dataset ....");
        	norm.init(fullIter, TASK);
        }
        
        //if the input is masked/postprocessed update the input size for the model creation
        if(parser.getMaskHiddenFeatures()) {
        	numInputs -= CatanFeatureMaskingUtil.droppedFeaturesCount;
        	actInputSize -= CatanFeatureMaskingUtil.droppedFeaturesCount;
        }
        
        log.info("Build model....");
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(seed)
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .iterations(iterations)
                .learningRate(learningRate)
                .rmsDecay(0.9)
                .list(2)
                .layer(0, new DenseLayer.Builder()
                        .nIn(numInputs + actInputSize - 2)
                        .nOut(256)
                        .activation("sigmoid")
                        .weightInit(WeightInit.XAVIER)
                        .updater(Updater.RMSPROP)
                        .build())
                .layer(1, new DenseLayer.Builder()
                        .nIn(256)
                        .nOut(256)
                        .activation("relu")
                        .weightInit(WeightInit.XAVIER)
                        .updater(Updater.RMSPROP)
                        .build())
                .layer(2, new OutputLayer.Builder(LossFunction.XENT)
                        .nIn(256)
                        .nOut(1)
                        .activation("sigmoid")
                        .weightInit(WeightInit.XAVIER)
                        .updater(Updater.RMSPROP)
                        .build())
                .backprop(true).pretrain(false)
                .build();

        MultiLayerNetwork model;

        log.info("Initialise cross-validation....");
        CrossValidationUtils cv = new CrossValidationUtils(fullIter, FOLDS, nSamples);
        CatanPlotter[] plotter = new CatanPlotter[FOLDS];
        
        for(int k = 0; k < FOLDS; k++){
        	log.info("Starting fold " + k);
        	cv.setCurrentFold(k);
        	PreloadedCatanDataSetIterator trainIter = cv.getTrainIterator();
        	PreloadedCatanDataSetIterator testIter = cv.getTestIterator();
        	plotter[k] = new CatanPlotter(parser.getTask());
        	
        	//read all data for training into a datastructure
            ArrayList<CatanDataSet> train = new ArrayList<>(nSamples);
        	while(trainIter.hasNext()){
        		train.add(trainIter.next());
        	}
        	trainIter.reset();
            //read all data for evaluation on training set
            ArrayList<CatanDataSet> trainSamples = new ArrayList<>(nSamples);
        	while(trainIter.hasNext()){
        		trainSamples.add(trainIter.next(1));
        	}
        	trainIter.reset();
        	
            //read all data for evaluation into a datastructure
            ArrayList<CatanDataSet> evalData = new ArrayList<>(nSamples);
            while(testIter.hasNext()){
        		evalData.add(testIter.next());
        	}
        	testIter.reset();
        	
        	//reset model for each fold
            model = new MultiLayerNetwork(conf);
            model.init();
	        
            log.info("Train model....");
	        for( int i=0; i<epochs; i++ ) {
	        	for(CatanDataSet d : train){
	        		DataSet ds = DataUtils.turnToSAPairsDS(d,metricW,labelW);
	            	if(NORMALISATION)
	            		norm.normalizeZeroMeanUnitVariance(ds);
	            	model.fit(ds);
	        	}
	            log.info("*** Completed epoch {} ***", i);
	            
	            CatanEvaluation eval = new CatanEvaluation(maxActions);
	            CatanEvaluation tEval = new CatanEvaluation(maxActions);
	            
	            log.info("Evaluate model on evaluation set....");
	            for(CatanDataSet d : evalData){
	            	DataSet ds = DataUtils.turnToSAPairsDS(d,metricW,labelW);
	            	if(NORMALISATION)
	            		norm.normalizeZeroMeanUnitVariance(ds);
	            	if(softmaxOverOut){
		            	INDArray output = model.output(ds.getFeatureMatrix());
		    	    	SoftMax softMax = new SoftMax(output);
		    	    	softMax.exec(1);
		    	    	if(selectMax){
		    	    		int n = Nd4j.getBlasWrapper().iamax(softMax.z().getColumn(0));
		    	    		double[] out = new double[d.getSizeOfLegalActionSet().getInt(0)];
		    	    		out[n] = 1;
		    	    		eval.eval(DataUtils.computeLabels(d), Nd4j.create(out));
		    	    	}else{//let the evaluation to select the max
		    	    		eval.eval(DataUtils.computeLabels(d), softMax.z().transpose());
		    	    	}
	            	}else{
	            		INDArray output = model.output(ds.getFeatureMatrix());
		    	    	if(selectMax){
		    	    		int n = Nd4j.getBlasWrapper().iamax(output.getColumn(0));
		    	    		double[] out = new double[d.getSizeOfLegalActionSet().getInt(0)];
		    	    		out[n] = 1;
		    	    		eval.eval(DataUtils.computeLabels(d), Nd4j.create(out));
		    	    	}else{//let the evaluation to select the max
		    	    		eval.eval(DataUtils.computeLabels(d), output.transpose());
		    	    	}
	            	}
	            }
	            //once finished just output the result
	            System.out.println(eval.stats());
	            
	            log.info("Evaluate model on training set....");
	            for(CatanDataSet d : trainSamples){
	            	DataSet ds = DataUtils.turnToSAPairsDS(d,metricW,labelW);
	            	if(NORMALISATION)
	            		norm.normalizeZeroMeanUnitVariance(ds);
	            	if(softmaxOverOut){
		            	INDArray output = model.output(ds.getFeatureMatrix());
		    	    	SoftMax softMax = new SoftMax(output);
		    	    	softMax.exec(1);
		    	    	if(selectMax){
		    	    		int n = Nd4j.getBlasWrapper().iamax(softMax.z().getColumn(0));
		    	    		double[] out = new double[d.getSizeOfLegalActionSet().getInt(0)];
		    	    		out [n] = 1;
		    	    		tEval.eval(DataUtils.computeLabels(d), Nd4j.create(out));
		    	    	}else{
		    	    		tEval.eval(DataUtils.computeLabels(d), softMax.z().transpose());
		    	    	}
	            	}else{
		            	INDArray output = model.output(ds.getFeatureMatrix());
		            	if(selectMax){
		    	    		int n = Nd4j.getBlasWrapper().iamax(output.getColumn(0));
		    	    		double[] out = new double[d.getSizeOfLegalActionSet().getInt(0)];
		    	    		out[n] = 1;
		    	    		tEval.eval(DataUtils.computeLabels(d), Nd4j.create(out));
		    	    	}else{//let the evaluation to select the max
		    	    		tEval.eval(DataUtils.computeLabels(d), output.transpose());
		    	    	}
		            }
	            }
	            //once finished just output the result
	            System.out.println(tEval.stats());
	            plotter[k].addData(eval.score(), tEval.score(), eval.accuracy(), tEval.accuracy());
	            plotter[k].addRanks(tEval.getRank(), eval.getRank());
	        }
	        
            //write current results and move files so these won't be overwritten
	        plotter[k].writeAll();
            File dirFrom = new File(CatanPlotter.RESULTS_DIR);
            File[] files = dirFrom.listFiles();
            File dirTo = new File("" + k);
            dirTo.mkdirs();
            for(File f : files){
            	FileUtils.moveFileToDirectory(f, dirTo, false);
            }
	    }
        cv.writeResults(plotter, TASK);
    }
	
}

