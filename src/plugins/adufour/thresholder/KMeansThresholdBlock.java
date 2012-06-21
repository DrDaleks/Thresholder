package plugins.adufour.thresholder;

import icy.plugin.abstract_.Plugin;
import icy.plugin.interface_.PluginBundled;
import icy.sequence.Sequence;
import plugins.adufour.blocks.lang.Block;
import plugins.adufour.blocks.util.BlockInfo;
import plugins.adufour.blocks.util.VarList;
import plugins.adufour.vars.gui.model.IntegerRangeModel;
import plugins.adufour.vars.lang.VarDoubleArray;
import plugins.adufour.vars.lang.VarInteger;
import plugins.adufour.vars.lang.VarSequence;
import plugins.adufour.vars.util.VarException;

public class KMeansThresholdBlock extends Plugin implements Block, BlockInfo, PluginBundled
{
    VarSequence    input      = new VarSequence("Input", null);
    VarInteger     channel    = new VarInteger("Channel", 0);
    VarInteger     nbClasses  = new VarInteger("Classes", 2);
    
    VarDoubleArray thresholds = new VarDoubleArray("thresholds", new Double[] {});
    
    @Override
    public void run()
    {
        Sequence seq = input.getValue();
        
        if (seq == null) throw new VarException("Thresholder: no sequence selected");
        
        int c = channel.getValue();
        int sizeC = seq.getSizeC();
        
        if (c >= sizeC) throw new VarException("Thresholder: invalid channel (" + c + "³" + sizeC + ")");
        
        int nC = nbClasses.getValue();
        
        double[][] thrs = KMeans.computeKMeansThresholds(seq, nC);
        
        Double[] result = new Double[nC - 1];
        for (int i = 0; i < result.length; i++)
            result[i] = thrs[c][i];
        
        thresholds.setValue(result);
    }
    
    @Override
    public void declareInput(VarList inputMap)
    {
        nbClasses.setDefaultEditorModel(new IntegerRangeModel(2, 2, 65535, 1));
        channel.setDefaultEditorModel(new IntegerRangeModel(0, 0, 65535, 1));
        
        inputMap.add(input);
        inputMap.add(channel);
        inputMap.add(nbClasses);
    }
    
    @Override
    public void declareOutput(VarList outputMap)
    {
        outputMap.add(thresholds);
    }
    
    @Override
    public String getName()
    {
        return "Intensity KMeans";
    }
    
    @Override
    public String getDescription()
    {
        return "Computes the thresholds on the intensity histograms using the KMeans algorithm";
    }

    @Override
    public String getMainPluginClassName()
    {
        return Thresholder.class.getCanonicalName();
    }
    
}
