package plugins.adufour.thresholder;

import icy.sequence.Sequence;
import icy.type.collection.array.Array1DUtil;

public class KMeans
{
	public static final int DEFAULT_KMEANS_BINS = 255;

	/**
	 * Calculates the optimal thresholds on the input data for the given number of classes and
	 * default bins size
	 * 
	 * @param input
	 *            the input sequence
	 * @param nbClasses
	 *            the number of classes to extract
	 * @return an array of thresholds of size [input.getSizeC()][nbClasses-1]
	 */
	public static double[][] computeKMeansThresholds(Sequence input, int nbClasses)
	{
		return computeKMeansThresholds(input, nbClasses, DEFAULT_KMEANS_BINS);
	}

	/**
	 * Calculates the optimal thresholds on the input data for the given number of classes and bins
	 * size
	 * 
	 * @param input
	 *            the input sequence
	 * @param nbClasses
	 *            the number of classes to extract
	 * @param binPrecision
	 *            the size of the histogram bins (higher is slower but more precise)
	 * @return an array of thresholds of size [input.getSizeC()][nbClasses-1]
	 */
	public static double[][] computeKMeansThresholds(Sequence input, int nbClasses, int binPrecision)
	{
		double[][] thrs = new double[input.getSizeC()][];

		for (int c = 0; c < input.getSizeC(); c++)
			thrs[c] = computeKMeansThresholds(input, c, nbClasses, binPrecision);

		return thrs;
	}

	/**
	 * Calculates the optimal thresholds on the specified channel of the input data for the given
	 * number of classes and bins size
	 * 
	 * @param input
	 *            the input sequence
	 * @param c
	 *            the channel on which to compute the threshold(s)
	 * @param nbClasses
	 *            the number of classes to extract
	 * @param binPrecision
	 *            the size of the histogram bins (higher is slower but more precise)
	 * @return an array of thresholds for the given channel, of size [nbClasses-1]
	 */
	public static double[] computeKMeansThresholds(Sequence input, int c, int nbClasses, int binPrecision)
	{
		double[] thresholds = new double[nbClasses - 1];

		input.updateChannelsBounds(true);
		double[] minmax = input.getChannelBounds(c);
		double min = minmax[0], max = minmax[1];
		double fact = (binPrecision - 1) / (max - min);
		double[] histo = new double[binPrecision];

		// histogram computation
		{
			double[] sliceXY = new double[input.getSizeX() * input.getSizeY()];

			for (int t = 0; t < input.getSizeT(); t++)
				for (int z = 0; z < input.getSizeZ(); z++)
				{
					Array1DUtil.arrayToDoubleArray(input.getDataXY(t, z, c), sliceXY, false);
					for (double d : sliceXY)
						histo[(int) ((d - min) * fact)]++;
				}
		}

		int[] centers = kMeans_Histogram1D(histo, nbClasses);

		// Compute thresholds between class centers

		java.util.Arrays.sort(centers);
		for (int k = 1; k < nbClasses; k++)
		{
			thresholds[k - 1] = min + (centers[k - 1] + (centers[k] - centers[k - 1]) / 2.0) / fact;
		}

		return thresholds;
	}

	/**
	 * Calculates the optimal thresholds on the specified channel of the input data for the given
	 * number of classes and bins size
	 * 
	 * @param input
	 *            the input sequence
	 * @param c
	 *            the channel on which to compute the threshold(s)
	 * @param nbClasses
	 *            the number of classes to extract
	 * @param binPrecision
	 *            the size of the histogram bins (higher is slower but more precise)
	 * @return an array of thresholds for the given channel, of size [nbClasses-1]
	 */
	public static double[] computeKMeansThresholds(Sequence input, int c, int t, int nbClasses, int binPrecision)
	{
		double[] thresholds = new double[nbClasses - 1];

		// compute min/max on the current stack
        double min = input.getImage(t, 0).getChannelMin(c);
        double max = input.getImage(t, 0).getChannelMax(c);
        
        for (int z = 1; z < input.getSizeZ(); z++)
        {
            double[] sliceBounds = input.getImage(t, z).getChannelBounds(c);
            if (sliceBounds[0] < min) min = sliceBounds[0];
            if (sliceBounds[1] > max) max = sliceBounds[1];
        }
		
		double fact = (binPrecision - 1) / (max - min);
		double[] histo = new double[binPrecision];

		// histogram computation
		{
			double[] sliceXY = new double[input.getSizeX() * input.getSizeY()];

			for (int z = 0; z < input.getSizeZ(); z++)
			{
				Array1DUtil.arrayToDoubleArray(input.getDataXY(t, z, c), sliceXY, false);
				for (double d : sliceXY)
					histo[(int) ((d - min) * fact)]++;
			}
		}

		int[] centers = kMeans_Histogram1D(histo, nbClasses);

		// Compute thresholds between class centers

		java.util.Arrays.sort(centers);
		for (int k = 1; k < nbClasses; k++)
		{
			thresholds[k - 1] = min + (centers[k - 1] + (centers[k] - centers[k - 1]) / 2.0) / fact;
		}

		return thresholds;
	}

	public static double[][] computeKMeansThresholds(Sequence inSeq, int c, boolean timeDependent, short nbClasses, int nbBins)
	{
		double[][] thrs = new double[inSeq.getSizeT()][];

		if (timeDependent)
		{
			for (int t = 0; t < thrs.length; t++)
			{
				thrs[t] = computeKMeansThresholds(inSeq, c, t, nbClasses, nbBins);
			}
		}
		else
		{
			double[] thr = computeKMeansThresholds(inSeq, c, nbClasses, nbBins);

			for (int i = 0; i < thrs.length; i++)
				thrs[i] = thr;
		}

		return thrs;
	}

	/**
	 * KMeans classification algorithm, optimized for 1D histogram data. The algorithm is
	 * initialized by spacing the class centers equally
	 * 
	 * @param histogram
	 *            the histogram to classify
	 * @param nbClasses
	 *            the number of classes to extract
	 * @return the optimal class centers after convergence
	 */
	public static int[] kMeans_Histogram1D(double[] histogram, int nbClasses)
	{
		int[] centers = new int[nbClasses];
		double[] sums = new double[nbClasses];
		double[] nbElements = new double[nbClasses];

		// basic class initialization : regularly divide the space

		for (int i = 0; i < nbClasses; i++)
		{
			centers[i] = (int) ((histogram.length - 1f) * (i + 1f) / (nbClasses + 1f));
		}

		// main loop

		boolean convergence = false;

		while (!convergence)
		{
			// assume the convergence is reached
			// (invalidate this assumption later if class means move)
			convergence = true;

			java.util.Arrays.fill(nbElements, 0);
			java.util.Arrays.fill(sums, 0);

			for (int i = 0; i < histogram.length; i++)
			{
				int closestClass = 0;
				double minDistance = Double.MAX_VALUE;

				// compute the shortest distance from the current bin
				// to a class center and assign the bin to that class
				for (int k = 0; k < nbClasses; k++)
				{
					double distance = Math.abs(i - centers[k]);

					if (distance < minDistance)
					{
						minDistance = distance;
						closestClass = k;
					}
				}

				// to update each class center to the mean,
				// accumulate both number and sum per class
				double nbElemInCurrentBin = histogram[i];
				sums[closestClass] += i * nbElemInCurrentBin;
				nbElements[closestClass] += nbElemInCurrentBin;
			}

			// once all bins have been assigned to a class,
			// the class centers can be moved toward the new means

			for (int k = 0; k < nbClasses; k++)
			{
				int oldCenter = centers[k];
				int newCenter = (int) (sums[k] / nbElements[k]);

				convergence &= (oldCenter == newCenter);

				centers[k] = newCenter;
			}
		}

		return centers;
	}
}
