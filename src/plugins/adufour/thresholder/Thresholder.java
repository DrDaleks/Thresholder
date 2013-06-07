package plugins.adufour.thresholder;

import icy.image.IcyBufferedImage;
import icy.roi.BooleanMask2D;
import icy.roi.ROI;
import icy.roi.ROI2DArea;
import icy.sequence.Sequence;
import icy.type.DataType;
import icy.type.collection.array.Array1DUtil;

import java.awt.Rectangle;
import java.util.ArrayList;

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
import plugins.adufour.roi.ROI3DArea;
import plugins.adufour.vars.lang.VarROIArray;
import plugins.adufour.vars.lang.VarSequence;
import plugins.adufour.vars.util.VarException;

public class Thresholder extends EzPlug implements Block
{
    private enum ThresholdMethod
    {
        MANUAL, K_MEANS
    }
    
    private enum ThresholdOutput
    {
        SEQUENCE, ROI
    }
    
    private EzVarSequence              in            = new EzVarSequence("Input");
    private EzVarInteger               channel       = new EzVarInteger("Channel", 0, 0, 65535, 1);
    private EzVarEnum<ThresholdMethod> method        = new EzVarEnum<ThresholdMethod>("Method", ThresholdMethod.values(), ThresholdMethod.MANUAL);
    private EzVarInteger               nbClasses     = new EzVarInteger("K-means classes", 2, KMeans.DEFAULT_KMEANS_BINS, 1);
    private EzVarDoubleArrayNative     thresholds    = new EzVarDoubleArrayNative("Manual thresholds", new double[][] { new double[] { 100, 200 } }, true);
    private EzVarBoolean               timeDependent = new EzVarBoolean("Process frames independently", false);
    private EzVarEnum<ThresholdOutput> outputType    = new EzVarEnum<ThresholdOutput>("Output as", ThresholdOutput.values(), ThresholdOutput.SEQUENCE);
    private EzVarBoolean               inPlace       = new EzVarBoolean("Overwrite input", false);
    
    private VarSequence                outLabels     = new VarSequence("Binary output", null);
    private VarROIArray                outROI        = new VarROIArray("ROI");
    
    private boolean                    blockMode     = false;
    
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
                    if (sizeC == 1)
                    {
                        channel.setVisible(false);
                        channel.setValue(0);
                    }
                    else
                    {
                        channel.setVisible(true);
                        channel.setMaxValue(sizeC - 1);
                    }
                    
                    timeDependent.setVisible(newValue.getSizeT() > 1);
                }
            }
        });
        
        super.addEzComponent(method);
        
        method.addVisibilityTriggerTo(nbClasses, ThresholdMethod.K_MEANS);
        super.addEzComponent(nbClasses);
        
        method.addVisibilityTriggerTo(thresholds, ThresholdMethod.MANUAL);
        super.addEzComponent(thresholds);
        
        method.addVisibilityTriggerTo(timeDependent, ThresholdMethod.K_MEANS);
        super.addEzComponent(timeDependent);
        
        super.addEzComponent(outputType);
        
        outputType.addVisibilityTriggerTo(inPlace, ThresholdOutput.SEQUENCE);
        super.addEzComponent(inPlace);
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
                
                for (int i = 0; i < _thrs.length; i++)
                    _thrs[i] = thrs;
                
                break;
            }
            case K_MEANS:
            {
                _thrs = KMeans.computeKMeansThresholds(inSeq, c, timeDependent.getValue(), nbClasses.getValue().shortValue(), KMeans.DEFAULT_KMEANS_BINS);
                break;
            }
            default:
                throw new UnsupportedOperationException(algorithm + " method");
        }
        
        if (blockMode)
        {
            if (outLabels.isReferenced())
            {
                Sequence sOUT = threshold(inSeq, c, _thrs, false);
                sOUT.setName(inSeq.getName() + "_thresholded");
                outLabels.setValue(sOUT);
            }
            
            if (outROI.isReferenced())
            {
                outROI.setValue(threshold(inSeq, c, _thrs));
            }
        }
        else
        {
            switch (outputType.getValue())
            {
                case SEQUENCE:
                    Sequence sOUT = threshold(inSeq, c, _thrs, inPlace.getValue());
                    sOUT.setName(inSeq.getName() + "_thresholded");
                    if (inPlace.getValue())
                    {
                        sOUT.dataChanged();
                    }
                    else
                    {
                        addSequence(sOUT);
                    }
                break;
                case ROI:
                    ROI[] rois = threshold(inSeq, c, _thrs);
                    for (ROI roi : rois)
                        inSeq.addROI(roi);
                break;
            }
        }
    }
    
    /**
     * Threshold the given sequence channel with the specified thresholds, and returns the result as
     * a labeled sequence.<br>
     * Note: thresholds are inclusive: values equal to a threshold are considered as
     * "inside the object"
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
    
    /**
     * Threshold the given sequence channel with the specified thresholds, and returns the result as
     * a labeled sequence.<br>
     * Note: thresholds are inclusive: values equal to a threshold are considered as "above"<br>
     * 
     * @param input
     * @param c
     * @param thresholds
     *            a list of thresholds for each time point of the input sequence
     * @param inPlace
     * @return
     */
    public static Sequence threshold(Sequence input, int c, double[][] thresholdsT, boolean inPlace)
    {
        if (input == null) throw new IllegalArgumentException("Thresholder: no input sequence given");
        if (c >= input.getSizeC()) throw new IllegalArgumentException("Thresholder: input sequence has no channel #" + c);
        
        Sequence output = inPlace ? input : new Sequence();
        
        DataType dataType = input.getDataType_();
        
        int length = input.getSizeX() * input.getSizeY();
        
        output.beginUpdate();
        
        int maxClass = 0;
        
        for (int t = 0; t < input.getSizeT(); t++)
        {
            double[] thresholds = thresholdsT[t];
            
            if (thresholds == null || thresholds.length == 0) throw new IllegalArgumentException("Thresholder: no thresholds given");
            
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
     * a list of regions of interest (ROI).<br>
     * Note: thresholds are inclusive: values equal to a threshold are considered as
     * "inside the object"
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
    public static ROI[] threshold(Sequence input, int c, double[] thresholds)
    {
        double[][] thresholdsT = new double[input.getSizeT()][];
        for (int t = 0; t < thresholdsT.length; t++)
            thresholdsT[t] = thresholds;
        
        return threshold(input, c, thresholdsT);
    }
    
    /**
     * Threshold the given sequence channel with the specified thresholds, and returns the result as
     * a labeled sequence.<br>
     * Note: thresholds are inclusive: values equal to a threshold are considered as "above"<br>
     * 
     * @param input
     * @param c
     * @param thresholds
     *            a list of thresholds for each time point of the input sequence
     * @param inPlace
     * @return
     */
    public static ROI[] threshold(Sequence input, int c, double[][] thresholdsOverTime)
    {
        if (input == null) throw new IllegalArgumentException("Thresholder: no input sequence given");
        if (c >= input.getSizeC()) throw new IllegalArgumentException("Thresholder: input sequence has no channel #" + c);
        
        int sizeT = input.getSizeT();
        int sizeX = input.getSizeX();
        int sizeY = input.getSizeY();
//        int sizeC = input.getSizeC();
        int sliceSize = sizeX * sizeY;
        
        ArrayList<ROI> output = new ArrayList<ROI>(sizeT);
        
        DataType dataType = input.getDataType_();
        
        for (int t = 0; t < sizeT; t++)
        {
            double[] thresholds = thresholdsOverTime[t];
            
            if (thresholds == null || thresholds.length == 0) throw new IllegalArgumentException("Thresholder: no thresholds given");
            
            int depth = input.getSizeZ(t);
            
            int lastThresholdIndex = thresholds.length - 1;
            
            // initialize one mask per class
            BooleanMask2D[][] masks = new BooleanMask2D[depth][thresholds.length];
            double thr0 = thresholds[0];
            
            // keep a list of valid (non-empty slices)
            boolean[] isValidSlice = new boolean[depth];
            
            for (int z = 0; z < depth; z++)
            {
                for (int thr = 0; thr < thresholds.length; thr++)
                    masks[z][thr] = new BooleanMask2D(new Rectangle(sizeX, sizeY), new boolean[sliceSize]);
                
                BooleanMask2D[] masks2D = masks[z];
                
                Object _in2D = input.getDataXY(t, z, c);
                
                withTheNextPixel: for (int i = 0; i < sliceSize; i++)
                {
                    double val = Array1DUtil.getValue(_in2D, i, dataType);
                    
                    // background
                    if (val < thr0) continue withTheNextPixel;
                    
                    // treat the n-class case first
                    if (lastThresholdIndex > 0)
                    {
                        // browse thresholds from highest to lowest (above 1 to save one test)
                        // assign the first positive match and break the loop
                        for (int thr = lastThresholdIndex; thr > 0; thr--)
                            if (val >= thresholds[thr])
                            {
                                masks2D[thr].mask[i] = true;
                                if (!isValidSlice[z]) isValidSlice[z] = true;
                                continue withTheNextPixel;
                            }
                    }
                    
                    // last class (1 => index 0)
                    masks2D[0].mask[i] = true;
                    if (!isValidSlice[z]) isValidSlice[z] = true;
                }
            }
            
            for (int thr = 0; thr < thresholds.length; thr++)
            {
                ROI3DArea area3D = null;
                ROI2DArea area2D = null;
                
                for (int z = 0; z < depth; z++)
                {
                    if (!isValidSlice[z]) continue;
                    
                    if (area3D == null) area3D = new ROI3DArea();
                    
                    area2D = new ROI2DArea(masks[0][thr]);
                    area2D.setName("[T=" + t + "] Threshold: " + thresholds[thr]);
                    // this line doesn't work in Icy 1.3.6.0
                    // if (sizeC > 1) area2D.setC(c);
                    area2D.setT(t);
                    
                    if (depth > 1) area3D.addROI2D(z, area2D);
                }
                
                if (depth == 1)
                {
                    if (area2D != null) output.add(area2D);
                }
                else
                {
                    // this line doesn't work in Icy 1.3.6.0
                    // if (sizeC > 1) area3D.setC(c);
                    area3D.setT(t);
                    area3D.setName("[T=" + t + "] threshold: " + thresholds[thr]);
                    output.add(area3D);
                }
            }
        }
        
        return output.toArray(new ROI[output.size()]);
    }
    
    public void clean()
    {
    }
    
    @Override
    public void declareInput(VarList inputMap)
    {
        blockMode = true;
        inputMap.add(in.getVariable());
        inputMap.add(channel.getVariable());
        inputMap.add(thresholds.getVariable());
    }
    
    @Override
    public void declareOutput(VarList outputMap)
    {
        outputMap.add("output", outLabels);
        outputMap.add(outROI);
    }
    
}
