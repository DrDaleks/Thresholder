package plugins.adufour.thresholder;

import icy.image.IcyBufferedImage;
import icy.sequence.Sequence;
import icy.type.DataType;
import icy.type.collection.array.Array1DUtil;
import plugins.adufour.blocks.lang.Block;
import plugins.adufour.blocks.util.VarList;
import plugins.adufour.ezplug.EzPlug;
import plugins.adufour.ezplug.EzVar;
import plugins.adufour.ezplug.EzVarBoolean;
import plugins.adufour.ezplug.EzVarDoubleArrayNative;
import plugins.adufour.ezplug.EzVarEnum;
import plugins.adufour.ezplug.EzVarInteger;
import plugins.adufour.ezplug.EzVarListener;
import plugins.adufour.ezplug.EzVarSequence;
import plugins.adufour.vars.lang.VarSequence;
import plugins.adufour.vars.util.VarException;

public class Thresholder extends EzPlug implements Block
{
    private enum ThresholdMethod
    {
        MANUAL, K_MEANS
    }

    private EzVarSequence              in            = new EzVarSequence("Input");
    private EzVarInteger               channel       = new EzVarInteger("Channel", 0, 0, 65535, 1);
    private EzVarBoolean               inPlace       = new EzVarBoolean("Overwrite input", false);
    private EzVarEnum<ThresholdMethod> method        = new EzVarEnum<ThresholdMethod>("Method", ThresholdMethod.values(), ThresholdMethod.MANUAL);
    private EzVarInteger               nbClasses     = new EzVarInteger("K-means classes", 2, KMeans.DEFAULT_KMEANS_BINS, 1);
    private EzVarDoubleArrayNative     thresholds    = new EzVarDoubleArrayNative("Manual thresholds", new double[][] {}, true);
    private EzVarBoolean               timeDependent = new EzVarBoolean("Independent frames", false);

    private VarSequence                result        = new VarSequence("output", null);

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

        super.addEzComponent(timeDependent);
    }

    @Override
    public void execute()
    {
        Sequence inSeq = in.getValue(true);

        int c = channel.getValue();

        if (c >= inSeq.getSizeC()) throw new VarException("Thresholder: invalid channel (" + c + ")");

        ThresholdMethod algorithm = method.getValue();

        double[][] _thrs = new double[inSeq.getSizeT()][];

        switch (method.getValue())
        {
        case MANUAL:
        {
            double[] thrs = thresholds.getValue(true);

            if (thrs.length == 0) throw new VarException("No threshold(s) indicated");

//            double[] _thr0 = new double[thrs.length];
//            for (int i = 0; i < thrs.length; i++)
//                _thr0[i] = thrs[i].doubleValue();

            for (int i = 0; i < _thrs.length; i++)
                _thrs[i] = thrs;//_thr0;

            break;
        }
        case K_MEANS:
        {
            _thrs = KMeans.computeKMeansThresholds(inSeq, c, true, nbClasses.getValue().shortValue(), KMeans.DEFAULT_KMEANS_BINS);
            break;
        }
        default:
            throw new UnsupportedOperationException(algorithm + " method");
        }

        Sequence s = threshold(inSeq, c, _thrs, inPlace.getValue());

        if (inPlace.getValue())
            s.dataChanged();
        else if (!isHeadLess()) addSequence(s);

        result.setValue(s);
    }

    /**
     * Threshold the given sequence channel with the specified thresholds, and returns the result as
     * a labeled sequence.<br>
     * Note: thresholds are inclusive: values equal to a threshold are considered as "above"<br>
     * 
     * @param input
     * @param c
     * @param thresholds
     * @param inPlace
     * @return
     */
    public static Sequence threshold(Sequence input, int c, double[][] thresholdsT, boolean inPlace)
    {
        Sequence output = inPlace ? input : new Sequence();

        DataType dataType = input.getDataType_();

        int length = input.getSizeX() * input.getSizeY();

        output.beginUpdate();

        int maxClass = 0;

        for (int t = 0; t < input.getSizeT(); t++)
        {
            double[] thresholds = thresholdsT[t];
            double thr0 = thresholds[0];
            int maxThresholdIndex = thresholds.length - 1;
            if (thresholds.length > maxClass) maxClass = thresholds.length;

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
        output.getColorModel().setComponentAbsBounds(inPlace ? c : 0, 0, maxClass);
        output.getColorModel().setComponentUserBounds(inPlace ? c : 0, 0, maxClass);
        // output.updateComponentsBounds(true, true);

        return output;
    }

    /**
     * Threshold the given sequence channel with the specified thresholds, and returns the result as
     * a labeled sequence.<br>
     * Note: thresholds are inclusive: values equal to a threshold are considered as "above"
     * 
     * @param input
     *            the sequence to threshold
     * @param c
     *            the channel to threshold
     * @param thresholds
     *            the list of thresholds (applied on all sequence frames)
     * @param inPlace
     *            true to replace the input by the thresholded data
     * @return the thresholded sequence (if inPlace is true, will return a reference to input)
     */
    public static Sequence threshold(Sequence input, int c, double[] thresholds, boolean inPlace)
    {
        double[][] thresholdsT = new double[input.getSizeT()][];
        for (int t = 0; t < thresholdsT.length; t++)
            thresholdsT[t] = thresholds;

        return threshold(input, c, thresholdsT, inPlace);
    }

    public void clean()
    {
    }

    @Override
    public void declareInput(VarList inputMap)
    {
        inputMap.add(in.getVariable());
        inputMap.add(channel.getVariable());
        inputMap.add(thresholds.getVariable());
    }

    @Override
    public void declareOutput(VarList outputMap)
    {
        outputMap.add(result);
    }

}
