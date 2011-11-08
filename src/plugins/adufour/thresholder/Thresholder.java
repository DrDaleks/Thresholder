package plugins.adufour.thresholder;

import icy.image.IcyBufferedImage;
import icy.sequence.Sequence;
import icy.type.DataType;
import icy.type.collection.array.Array1DUtil;
import plugins.adufour.ezplug.EzPlug;
import plugins.adufour.ezplug.EzVar;
import plugins.adufour.ezplug.EzVarBoolean;
import plugins.adufour.ezplug.EzVarDoubleArray;
import plugins.adufour.ezplug.EzVarEnum;
import plugins.adufour.ezplug.EzVarInteger;
import plugins.adufour.ezplug.EzVarListener;
import plugins.adufour.ezplug.EzVarSequence;

public class Thresholder extends EzPlug
{
	private enum ThresholdMethod
	{
		MANUAL, K_MEANS
	}
	
	private EzVarSequence				in			= new EzVarSequence("Input");
	private EzVarInteger				channel		= new EzVarInteger("Channel", 0, 0, 1, 1);
	private EzVarBoolean				inPlace		= new EzVarBoolean("In-place", false);
	private EzVarEnum<ThresholdMethod>	method		= new EzVarEnum<ThresholdMethod>("Method", ThresholdMethod.values());
	private EzVarInteger				nbClasses	= new EzVarInteger("K-means classes", 2, KMeans.DEFAULT_KMEANS_BINS, 1);
	private EzVarDoubleArray			thresholds	= new EzVarDoubleArray("Manual thresholds", new Double[][] {}, true);
	
	@Override
	public void initialize()
	{
		super.addEzComponent(in);
		super.addEzComponent(channel);
		
		in.addVarChangeListener(new EzVarListener<Sequence>()
		{
			@Override
			public void variableChanged(EzVar<Sequence> source, Sequence newValue)
			{
				if (newValue == null)
				{
					channel.setVisible(false);
				}
				else
				{
					int sizeC = newValue.getSizeC();
					switch (sizeC)
					{
						case 1:
						{
							channel.setVisible(false);
							channel.setValue(0);
							break;
						}
						default:
						{
							channel.setVisible(true);
							channel.setMaxValue(sizeC - 1);
						}
					}
				}
			}
		});
		
		super.addEzComponent(inPlace);
		
		super.addEzComponent(method);
		
		super.addEzComponent(nbClasses);
		method.addVisibilityTriggerTo(nbClasses, ThresholdMethod.K_MEANS);
		
		super.addEzComponent(thresholds);
		method.addVisibilityTriggerTo(thresholds, ThresholdMethod.MANUAL);
		
		super.setTimeDisplay(true);
	}
	
	@Override
	public void execute()
	{
		Sequence inSeq = in.getValue();
		
		int c = channel.getValue();
		
		ThresholdMethod algorithm = method.getValue();
		
		double[] _thrs = null;
		
		switch (method.getValue())
		{
			case MANUAL:
			{
				Double[] thrs = thresholds.getValue();
				_thrs = new double[thrs.length];
				for (int i = 0; i < thrs.length; i++)
					_thrs[i] = thrs[i].doubleValue();
				
				break;
			}
			case K_MEANS:
			{
				_thrs = KMeans.computeKMeansThresholds(inSeq, c, nbClasses.getValue().shortValue(), KMeans.DEFAULT_KMEANS_BINS);
				break;
			}
			default:
				throw new UnsupportedOperationException(algorithm + " method");
		}
		
		Sequence s = threshold(inSeq, c, _thrs, inPlace.getValue());
		
		if (inPlace.getValue())
			s.dataChanged();
		else addSequence(s);
		
	}
	
	/**
	 * Threshold the given sequence channel with the specified thresholds, and returns the result as
	 * a labeled sequence.<br>
	 * Note: thresholds are inclusive: values equal to a threshold are considered as "above"
	 * 
	 * @param input
	 * @param c
	 * @param thresholds
	 * @param inPlace
	 * @return
	 */
	public static Sequence threshold(Sequence input, int c, double[] thresholds, boolean inPlace)
	{
		Sequence output = inPlace ? input : new Sequence();
		
		DataType dataType = input.getDataType_();
		
		double thr0 = thresholds[0];
		int maxThresholdIndex = thresholds.length - 1;
		int length = input.getSizeX() * input.getSizeY();
		
		output.beginUpdate();
		
		for (int t = 0; t < input.getSizeT(); t++)
		{
			for (int z = 0; z < input.getSizeZ(); z++)
			{
				Object _in2D = input.getDataXY(t, z, c);
				
				IcyBufferedImage outSlice;
				
				if (inPlace)
				{
					outSlice = input.getImage(t, z);
				}
				else
				{
					outSlice = new IcyBufferedImage(input.getSizeX(), input.getSizeY(), 1, dataType);
					output.setImage(t, z, outSlice);
				}
				
				Object _out2D = outSlice.getDataXY(inPlace ? c : 0);
				
				withTheNextPixel: for (int i = 0; i < length; i++)
				{
					double val = Array1DUtil.getValue(_in2D, i, dataType);
					
					// background
					if (val < thr0)
					{
						if (inPlace)
						{
							Array1DUtil.setValue(_out2D, i, dataType, 0);
						}
						continue withTheNextPixel;
					}
					// special 2-class case
					if (maxThresholdIndex == 0)
					{
						Array1DUtil.setValue(_out2D, i, dataType, 1);
						continue withTheNextPixel;
					}
					
					// default n-class case
					
					// browse thresholds from highest to lowest (above 1 to save one test)
					// assign the first positive match and break the loop
					for (int thr = maxThresholdIndex; thr > 0; thr--)
						if (val >= thresholds[thr])
						{
							Array1DUtil.setValue(_out2D, i, dataType, thr + 1);
							continue withTheNextPixel;
						}
					
					// last possible case: class 1
					Array1DUtil.setValue(_out2D, i, dataType, 1);
				}
			}
		}
		
		output.endUpdate();
		
		return output;
	}
	
	public void clean()
	{
	}
	
}
