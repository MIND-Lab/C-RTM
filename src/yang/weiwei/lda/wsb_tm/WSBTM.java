package yang.weiwei.lda.wsb_tm;

import java.io.IOException;

import yang.weiwei.lda.LDA;
import yang.weiwei.lda.LDACfg;
import yang.weiwei.lda.LDAParam;
import yang.weiwei.lda.util.LDAResult;
import yang.weiwei.util.IOUtil;
import yang.weiwei.wsbm.WSBM;
import yang.weiwei.wsbm.WSBMParam;

public class WSBTM extends LDA
{
	protected double _alpha;
	
	protected double pi[][];
	protected int blockTopicCounts[][];
	protected int blockTokenCounts[];
	
	protected WSBM wsbm;
	
	public void readCorpus(String corpusFileName) throws IOException
	{
		super.readCorpus(corpusFileName);
		WSBMParam wsbmParam=new WSBMParam(param, numDocs);
		wsbm=new WSBM(wsbmParam);
	}
	
	public void readGraph(String graphFileName) throws IOException
	{
		wsbm.readGraph(graphFileName);
		wsbm.init();
	}
	
	protected void printParam()
	{
		super.printParam();
		param.printBlockParam("\t");
		wsbm.param.printParam("\t");
	}
	
	public void initialize()
	{
		super.initialize();
		initBlockAssigns();
	}
	
	public void initialize(String topicAssignFileName) throws IOException
	{
		super.initialize(topicAssignFileName);
		initBlockAssigns();
	}
	
	protected void initBlockAssigns()
	{
		if (wsbm.getNumEdges()==0) return;
		for (int doc=0; doc<numDocs; doc++)
		{
			for (int topic=0; topic<param.numTopics; topic++)
			{
				blockTopicCounts[wsbm.getBlockAssign(doc)][topic]+=corpus.get(doc).getTopicCount(topic);
				blockTokenCounts[wsbm.getBlockAssign(doc)]+=corpus.get(doc).getTopicCount(topic);
			}
		}
	}
	
	public void sample(int numIters)
	{
		for (int iteration=1; iteration<=numIters; iteration++)
		{
			for (int doc=0; doc<numDocs; doc++)
			{
				sampleBlock(doc);
				sampleDoc(doc);
			}
			computeLogLikelihood();
			perplexity=Math.exp(-logLikelihood/numTestWords);
			if (param.verbose)
			{
				IOUtil.println("<"+iteration+">"+"\tBlock Log-LLD: "+format(wsbm.getLogLikelihood())+
						"\tLog-LLD: "+format(logLikelihood)+"\tPPX: "+format(perplexity));
			}
		}
		
		if (type==TRAIN && param.verbose)
		{
			for (int topic=0; topic<param.numTopics; topic++)
			{
				IOUtil.println(topWordsByFreq(topic, 10));
			}
		}
	}
	
	protected void sampleBlock(int doc)
	{
		if (wsbm.getNumEdges()==0) return;
		int oldBlock=wsbm.getBlockAssign(doc);
		wsbm.sampleNode(doc);
		int newBlock=wsbm.getBlockAssign(doc);
		for (int topic=0; topic<param.numTopics; topic++)
		{
			blockTopicCounts[oldBlock][topic]-=corpus.get(doc).getTopicCount(topic);
			blockTokenCounts[oldBlock]-=corpus.get(doc).getTopicCount(topic);
			blockTopicCounts[newBlock][topic]+=corpus.get(doc).getTopicCount(topic);
			blockTokenCounts[newBlock]+=corpus.get(doc).getTopicCount(topic);
		}
	}
	
	protected void sampleDoc(int doc)
	{
		int oldTopic,newTopic,interval=getSampleInterval();
		for (int token=0; token<corpus.get(doc).docLength(); token+=interval)
		{
			oldTopic=unassignTopic(doc, token);
			if (wsbm.getNumEdges()>0)
			{
				blockTopicCounts[wsbm.getBlockAssign(doc)][oldTopic]--;
				blockTokenCounts[wsbm.getBlockAssign(doc)]--;
			}
			
			newTopic=sampleTopic(doc, token, oldTopic);
			
			assignTopic(doc, token, newTopic);
			if (wsbm.getNumEdges()>0)
			{
				blockTopicCounts[wsbm.getBlockAssign(doc)][newTopic]++;
				blockTokenCounts[wsbm.getBlockAssign(doc)]++;
			}
		}
	}
	
	protected double topicUpdating(int doc, int topic, int vocab)
	{
		double ratio=(blockTopicCounts[wsbm.getBlockAssign(doc)][topic]+_alpha)/
				(blockTokenCounts[wsbm.getBlockAssign(doc)]+_alpha*param.numTopics);
		if (wsbm.getNumEdges()==0) ratio=1.0/param.numTopics;
		if (type==TRAIN)
		{
			return (param.alphaSum*ratio+corpus.get(doc).getTopicCount(topic))*
					(param.beta+topics[topic].getVocabCount(vocab))/
					(param.beta*param.numVocab+topics[topic].getTotalTokens());
		}
		return (param.alphaSum*ratio+corpus.get(doc).getTopicCount(topic))*phi[topic][vocab];
	}
	
	public void addResults(LDAResult result)
	{
		super.addResults(result);
		result.add(LDAResult.BLOCKLOGLIKELIHOOD, wsbm.getLogLikelihood());
	}
	
	protected void computeLogLikelihood()
	{
		super.computeLogLikelihood();
		if (wsbm.getNumEdges()>0) wsbm.computeLogLikelihood();
	}
	
	protected void computePi()
	{
		for (int l=0; l<param.numBlocks; l++)
		{
			for (int topic=0; topic<param.numTopics; topic++)
			{
				pi[l][topic]=(_alpha+blockTopicCounts[l][topic])/(blockTokenCounts[l]+_alpha*param.numTopics);
			}
		}
	}
	
	protected void computeTheta()
	{
		computePi();
		for (int doc=0; doc<numDocs; doc++)
		{
			for (int topic=0; topic<param.numTopics; topic++)
			{
				theta[doc][topic]=(param.alphaSum*pi[wsbm.getBlockAssign(doc)][topic]+corpus.get(doc).getTopicCount(topic))/
						(param.alphaSum+getSampleSize(corpus.get(doc).docLength()));
			}
		}
	}
	
	public double[][] getBlockTopicDist()
	{
		return pi;
	}
	
	public void writeBlocks(String blockFileName) throws IOException
	{
		wsbm.writeBlocks(blockFileName);
	}
	
	public void printBlocks()
	{
		wsbm.printResults();
	}
	
	protected void initVariables()
	{
		super.initVariables();
		_alpha=param._alphaSum/param.numTopics;
		blockTopicCounts=new int[param.numBlocks][param.numTopics];
		blockTokenCounts=new int[param.numBlocks];
		pi=new double[param.numBlocks][param.numTopics];
	}
	
	public WSBTM(LDAParam parameters)
	{
		super(parameters);
	}
	
	public WSBTM(WSBTM LDATrain, LDAParam parameters)
	{
		super(LDATrain, parameters);
	}
	
	public WSBTM(String modelFileName, LDAParam parameters) throws IOException
	{
		super(modelFileName, parameters);
	}
	
	public static void main(String args[]) throws IOException
	{	
		String seg[]=Thread.currentThread().getStackTrace()[1].getClassName().split("\\.");
		String modelName=seg[seg.length-1];
		LDAParam parameters=new LDAParam(LDACfg.vocabFileName);
		parameters.updateAlpha=false;
		LDAResult trainResults=new LDAResult();
		LDAResult testResults=new LDAResult();
		
		WSBTM LDATrain=new WSBTM(parameters);
		LDATrain.readCorpus(LDACfg.trainCorpusFileName);
		LDATrain.readGraph(LDACfg.trainGraphFileName);
		LDATrain.initialize();
		LDATrain.sample(LDACfg.numTrainIters);
		LDATrain.addResults(trainResults);
//		LDATrain.writeModel(LDACfg.getModelFileName(modelName));
		
		WSBTM LDATest=new WSBTM(LDATrain, parameters);
//		WSBTM LDATest=new WSBTM(LDACfg.getModelFileName(modelName), parameters);
		LDATest.readCorpus(LDACfg.testCorpusFileName);
		LDATest.readGraph(LDACfg.testGraphFileName);
		LDATest.initialize();
		LDATest.sample(LDACfg.numTestIters);
		LDATest.addResults(testResults);
		
		trainResults.printResults(modelName+" Training Perplexity:", LDAResult.PERPLEXITY);
		testResults.printResults(modelName+" Test Perplexity:", LDAResult.PERPLEXITY);
	}
}
