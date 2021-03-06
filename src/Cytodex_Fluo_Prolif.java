//  This plugin extract branches of spherois and compute lengths branching ...


import Skeletonize3D_.Skeletonize3D_;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.*;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import ij.plugin.filter.Analyzer;
import ij.plugin.filter.BackgroundSubtracter;
import ij.plugin.filter.DifferenceOfGaussians;
import ij.plugin.filter.GaussianBlur;
import ij.plugin.filter.MaximumFinder;
import ij.plugin.filter.RankFilters;
import ij.plugin.frame.RoiManager;
import ij.process.AutoThresholder;
import ij.process.ImageConverter;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.StackConverter;
import ij.util.ArrayUtil;
import java.awt.Color;
import java.awt.Polygon;
import java.io.*;
import static java.lang.Math.sqrt;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import skeleton_analysis.AnalyzeSkeleton_;
import skeleton_analysis.Edge;
import skeleton_analysis.Point;
import skeleton_analysis.SkeletonResult;


public class Cytodex_Fluo_Prolif implements PlugIn {
	
    private final boolean canceled =false;
    private static int nbNucleus;   // total number of nucleus
    private static double deltaArea;   // spheroide extension ratio proliferation and spheroide area)
    private static double spheroidXcent, spheroidYcent, spheroidArea, spheroidFeret, prolifFeret, prolifArea;   // Centroid coord and area of spheroid and proliferation
    private static final ResultsTable table = new ResultsTable();
    private static boolean isStack;
    private static Roi spheroidRoi, prolifRoi;
    

// clear outside selection with true background
    public void clearOutside(ImagePlus img, Roi cropRoi) {
        ImageProcessor ip = img.getProcessor();
        ip.setColor(Color.BLACK);
        img.setActivated();
        if (isStack) {
            for(int z = 1; z < img.getNSlices(); z++) {
                ip.fillOutside(cropRoi);
            }
        }
        else {
            ip.fillOutside(cropRoi);
        }
        img.deleteRoi();
    }
    

    /*
    find deltaR ratio between spheroid and prolif ferets
    */

 public void getDeltaArea(ImagePlus img) {
        
        if (isStack) img.setSlice(2);
        Analyzer measure = new Analyzer(img, Measurements.AREA + Measurements.FERET, table);
        measure.measure();
        prolifArea = table.getValue("Area",0);
        prolifFeret = table.getValue("Feret",0);
	deltaArea = prolifArea / spheroidArea;
	table.reset();
        img.getProcessor().setColor(Color.BLACK);
        if (isStack) {  // do not fill DAPI channel do after Band pass filter in findNucleus
            for (int s = 2; s <= img.getNSlices(); s++) {
                img.setSlice(s);
                img.getProcessor().fill(img.getRoi());
            }
        }
        else {
            img.getProcessor().fill(img.getRoi());
            img.updateImage();
        }
	img.deleteRoi();
 }
 
// find spheroid centroid and area     
    public void getCentroid(ImagePlus img) {
        
        if (isStack) img.setSlice(2);
        Analyzer measure = new Analyzer(img,Measurements.AREA + Measurements.FERET,table);
        measure.measure();
	spheroidArea = table.getValue("Area",0);
        spheroidFeret = table.getValue("Feret",0);
	table.reset();
	
    }

    

/**
     * Returns the location of pixels clockwise along circumference
     * using Bresenham's Circle Algorithm
     * keep point only if pixel is inside image
     */
    public static ArrayList<Point> BresenhamCircle(int xc,int yc,int r) {    
        ArrayList<Point> ret = new ArrayList<Point>();
        int x,y,p;
        x=0;
        y=r;
        ret.add(new Point(xc+x,yc-y,0));
        p=3-(2*r);
        for(x=0;x<=y;x++) {
            if (p<0) {
                p=(p+(4*x)+6);
            }
            else {
                y=y-1;
                p=p+((4*(x-y)+10));
            }
            ret.add(new Point(xc+x,yc-y,0));
            ret.add(new Point(xc-x,yc-y,0));
            ret.add(new Point(xc+x,yc+y,0));
            ret.add(new Point(xc-x,yc+y,0));
            ret.add(new Point(xc+y,yc-x,0));
            ret.add(new Point(xc-y,yc-x,0));
            ret.add(new Point(xc+y,yc+x,0));
            ret.add(new Point(xc-y,yc+x,0));
        }
        return ret;
}


// compute local thickness and branches diameter
    public static double[] localThickness (ImagePlus imgMask, ImagePlus imgSkel, String dir , String fileName, int spheroid) {
        imgMask.show();
        Calibration cal = new Calibration();
        cal = imgMask.getCalibration();
        double vxWH = cal.pixelWidth;
// parameters for center, min, max and step radius 
        final double incStep = 50;              // every 50 microns
        double cx = spheroidXcent;
        double cy = spheroidYcent;
        double startRadius = (prolifFeret/2) + 1 ;
        final int wdth = imgMask.getWidth();
	final int hght = imgMask.getHeight();
	final double dx, dy, dz, maxEndRadius, stepRadius;      
        double [] radii;
        ArrayList<Double> diameters = new ArrayList<Double>();
        double [] meanDiameters;

        WindowManager.setCurrentWindow(imgMask.getWindow());
        IJ.run("Local Thickness (complete process)", "threshold=255");
// wait for end of local thickness process
        while (WindowManager.getImage("Branches_Mask_LocThk") == null) {
            IJ.wait(100);
        }
        ImagePlus imgLocalThickness = WindowManager.getImage("Branches_Mask_LocThk");
        imgLocalThickness.setCalibration(cal);
        dx = (cx<=wdth/2) ? (cx-wdth) : cx;
        dy = (cy<=hght/2) ? (cy-hght) : cy;
        maxEndRadius = Math.sqrt(dx*dx + dy*dy);
        stepRadius = incStep;

// Calculate how many samples will be taken
        final int size = (int) ((maxEndRadius-startRadius)/stepRadius)+1;
//  IJ.log(spheroidFerret+" ,"+startRadius+" ,"+stepRadius+" ,"+size);
        
// Create arrays for radii (in physical units) and intersection counts
        radii = new double[size];
        meanDiameters = new double[size];
        for (int i = 0; i < size; i++) {
                radii[i] = startRadius + i*stepRadius;
        }
 
        ArrayList<Point> ptCircle = new ArrayList<Point>();
       
// compute points on circle every step microns and store in array
        for (int r = 0; r < radii.length; r++) {
            ptCircle = BresenhamCircle((int)cal.getRawX(cx), (int)cal.getRawY(cy), (int)(radii[r]/cal.pixelWidth));            
//            Polygon polygon = new Polygon();
//            for (int i = 0; i < ptCircle.size(); i++) {
//                polygon.addPoint(ptCircle.get(i).x, ptCircle.get(i).y);
//            }
//            PolygonRoi circle = new PolygonRoi(polygon,PolygonRoi.POLYLINE);
//            imgLocalThickness.setRoi(circle, true);
//           new WaitForUserDialog("wait enlarge").show();
            for (int i = 0; i < ptCircle.size(); i++) {
                // check if pixel inside image
                int x = ptCircle.get(i).x;
                int y = ptCircle.get(i).y;
                if (((x > 0) || (x < wdth)) && ((y > 0) || (y < hght))) {
                    //inside skeleton branch
                    if (imgSkel.getProcessor().getPixelValue(x, y) == 255) { 
                        // read pixel value in localthickness image
                        double pixelValue = imgLocalThickness.getProcessor().getPixelValue(x, y);
                        diameters.add(pixelValue); 
                     }      
                 }                  
            }
            // calculate the mean diameters
            double avg = 0;
            for (double sum:diameters) {
                avg += sum;
            }
            meanDiameters[r] = avg/diameters.size();
        }
        imgLocalThickness.changes = false;
        FileSaver imgSave = new FileSaver(imgLocalThickness);
        imgSave.saveAsTiff(dir+fileName+"_Crop_"+spheroid+"_distMap.tif");
        imgLocalThickness.close();
        imgSkel.changes = false;
        imgSkel.close();
        imgSkel.flush();
        imgMask.changes = false;
        imgMask.close();
        imgMask.flush();
        return(meanDiameters);    
    }
   
/* count nucleus take the coordinates of pixel in nucleus image
/* if grey value = 0 in branchs mask then nucleus ++
*/

    public Polygon findNucleus(ImagePlus imgCrop, ImagePlus imgBranchs, String dir , String fileName, int spheroid) {
	nbNucleus = 0;
        int xCoor, yCoor;

        ImagePlus imgNucleus = new Duplicator().run(imgCrop, 1, 1);
        ImagePlus colorNucleus = imgNucleus.duplicate();
        ImageConverter imgConv = new ImageConverter(colorNucleus);
        imgConv.convertToRGB();


// run difference of Gaussians
        double sigma1 = 3, sigma2 = 1;
        DifferenceOfGaussians.run(imgNucleus.getProcessor(), sigma1, sigma2);
        IJ.run("Contrast Enhancer");
        imgNucleus.getProcessor().setColor(Color.BLACK);
        imgNucleus.setRoi(prolifRoi);
        imgNucleus.getProcessor().fill(imgNucleus.getRoi());
        imgNucleus.deleteRoi();
// find maxima
        ImageProcessor ipNucleus = imgNucleus.getProcessor();
        MaximumFinder findMax = new MaximumFinder();
        Polygon  nucleusPoly = findMax.getMaxima(ipNucleus, 2,false);        
        
        colorNucleus.setColor(Color.YELLOW);
        for (int i = 0; i < nucleusPoly.npoints; i++) {
		xCoor = (int)nucleusPoly.xpoints[i];
		yCoor = (int)nucleusPoly.ypoints[i];
		if ((int)imgBranchs.getProcessor().getPixelValue(xCoor,yCoor) == 255) { // if not in mask
                    nbNucleus++;
                    OvalRoi ptNucleus = new OvalRoi(xCoor, yCoor,4,4);
                    colorNucleus.getProcessor().draw(ptNucleus);
                    colorNucleus.updateAndDraw();                  
                }
	}
        FileSaver imgSave = new FileSaver(colorNucleus);
        imgSave.saveAsTiff(dir+fileName+"_Crop_"+spheroid+"_nucleus.tif");
        colorNucleus.close();
        imgNucleus.close();
        imgNucleus.flush();
        imgCrop.changes = false;
        imgCrop.close();
        imgCrop.flush();
        spheroidRoi = null;
        return nucleusPoly;
    }
    
// Substract background
    public void backgroundSubstract(ImagePlus img) {
        BackgroundSubtracter imgSubstract = new BackgroundSubtracter();
        if (isStack) {
            for (int s = 1;s <= img.getNSlices(); s++) { 
                img.setSlice(s);
                imgSubstract.rollingBallBackground(img.getProcessor(), 50, false, false, false, false, false);
            }
            img.updateAndRepaintWindow();
        }
        else imgSubstract.rollingBallBackground(img.getProcessor(), 50, false, false, false, false, false);
    }
    
    public double calculateDistance(ImagePlus img, Point point1, Point point2) {
        return Math.sqrt(  Math.pow( (point1.x - point2.x) * img.getCalibration().pixelWidth, 2) 
                          + Math.pow( (point1.y - point2.y) * img.getCalibration().pixelHeight, 2)
                          + Math.pow( (point1.z - point2.z) * img.getCalibration().pixelDepth, 2));
    }

    // Calculate lenght of branches after skeletonize
    public void analyzeSkel (ImagePlus img, int spheroid, BufferedWriter output, String fileNameNoExt) {
	int nbSkeleton;             // number of skeleton
        double totalLength = 0;     // total branch lenght/spheroid
        int totalBranches = 0;   // total number of branches/spheroid
        double euclideanDist = 0;   // euclidean distance
        int nbTubes = 0;            // number of tubes/spheroid = number of skeleton + number of junction
        double meanTortuosity;      // mean euclidean distance / branch length
        double sdTortuosite;        // sd euclidean distance / branch length
        int nbJunctions = 0;         // number of junctions / spheroid
        
        AnalyzeSkeleton_ analyzeSkeleton = new AnalyzeSkeleton_();
        AnalyzeSkeleton_.calculateShortestPath = true;
        analyzeSkeleton.setup("",img);
        SkeletonResult skeletonResults = analyzeSkeleton.run(AnalyzeSkeleton_.NONE,false,true,null,true,false);
        nbSkeleton = skeletonResults.getNumOfTrees();
        int[] branchNumbers = skeletonResults.getBranches();
        int[] junctionNumbers = skeletonResults.getJunctions();
        for (int b = 0; b < branchNumbers.length; b++) { 
                totalBranches += branchNumbers[b];
                nbJunctions += junctionNumbers[b];
        }
        float[] tortuosity = new float[totalBranches];
        for (int i = 0; i < nbSkeleton; i++) {
            ArrayList<Edge> listEdges;
            listEdges = skeletonResults.getGraph()[i].getEdges();
            for (int e = 0; e < listEdges.size(); e++) {
                totalLength += listEdges.get(e).getLength();
                euclideanDist = calculateDistance(img, listEdges.get(e).getV1().getPoints().get(0), listEdges.get(e).getV2().getPoints().get(0));
                tortuosity[e] = (float) (euclideanDist/listEdges.get(e).getLength());
            }
        }
        ij.util.ArrayUtil stats = new ArrayUtil(tortuosity);
        meanTortuosity = stats.getMean();
        sdTortuosite = sqrt(stats.getVariance());
        nbTubes = skeletonResults.getNumOfTrees() + nbJunctions;
       
        try {
            // write data
            output.write(fileNameNoExt + "\t" + spheroid + "\t" + nbSkeleton + "\t" + totalBranches + "\t" + totalLength +
                    "\t" + nbJunctions + "\t" + nbTubes + "\t" + meanTortuosity + "\t" + 
                    sdTortuosite + "\t" + nbNucleus + "\t" + spheroidFeret + "\t" + spheroidArea + "\t" + prolifArea + "\t" + deltaArea + "\n");
            output.flush();
        } catch (IOException ex) {
            Logger.getLogger(Cytodex_Fluo_Prolif.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    
    
    
    public void run(String arg) {
        try {
            if (canceled) {
                IJ.showMessage(" Pluging canceled");
                return;
            }           
            String imageDir = IJ.getDirectory("Choose Directory Containing TIF Files...");
            if (imageDir == null) return;
            File inDir = new File(imageDir);
            String [] imageFile = inDir.list();
            if (imageFile == null) return;
// create directory to store images
            String imgOutDir = imageDir+"Images/";
            File imgTmpDir = new File(imgOutDir);
            if (!imgTmpDir.isDirectory())
                imgTmpDir.mkdir();
// write headers for global results
            FileWriter fwAnalyze;
            fwAnalyze = new FileWriter(imageDir + "Analyze_skeleton_results.xls",false);
            BufferedWriter outputAnayze = new BufferedWriter(fwAnalyze);
            outputAnayze.write("Image\t#Spheroids\t#Skeletons\t#Branches\tTotal branch length\t#Junctions\t"
                    + "#Tubes\tMean Tortuosity\tSD Tortuosity\t#Cells\tSpheroid D\tSpheroid Area\tProlif Area\tdelta Area\n");
            outputAnayze.flush();
// write headers for Sholl diameters results
            FileWriter fwDiameter;
            fwDiameter = new FileWriter(imageDir + "Analyze_skeleton_diameters_results.xls",false);
            BufferedWriter outputDiameter = new BufferedWriter(fwDiameter);
            outputDiameter.write("Image\t#Spheroids\tMean Diameter(d0)\tMean Diameter(d+50)\tMean Diameter(d+100)"
                    + "\tMean Diameter(d+150)\tMean Diameter(d+200)\tMean Diameter(d+250)\tMean Diameter(d+300)"
                    +"\tMean Diameter(d+350)\tMean Diameter(d+400)\tMean Diameter(d+450)\tMean Diameter(d+500)\n");
            outputDiameter.flush();

            Duplicator imgDup = new Duplicator();
            for (int i = 0; i < imageFile.length; i++) {
                if (imageFile[i].endsWith(".tif")) {
                    String imagePath = imageDir + imageFile[i];
                    Opener imgOpener = new Opener();
                    ImagePlus imgOrg = imgOpener.openImage(imagePath);
                    if (imgOrg.getNSlices() > 1) {
                        isStack = true;
                    }
                    String fileNameWithOutExt = imageFile[i].substring(0, imageFile[i].length() - 4);

//convert to 8 bytes
                    if (isStack) {
                        new StackConverter(imgOrg).convertToGray8();
                    }
                    else {
                        new ImageConverter(imgOrg).convertToGray8();
                    }

                    imgOrg.show();
                    // auto contrast                    
                    for ( int s = 1; s <= imgOrg.getNSlices(); s++) {
                        imgOrg.setSlice(s);
                        IJ.run("Contrast Enhancer");   
                    }
                    if (RoiManager.getInstance() != null) RoiManager.getInstance().close();
                    RoiManager rm = new RoiManager();
                    new WaitForUserDialog("Select part(s) of image to analyze\nPress t to add selection to the ROI manager.").show();
                    
                    for (int r = 0; r < rm.getCount(); r++) {
                        WindowManager.setTempCurrentImage(imgOrg);
                        rm.select(r);
                        int [] roiType = new int[rm.getCount()];
                        roiType[r] = imgOrg.getRoi().getType();
                        IJ.run("Duplicate...", "title=Crop duplicate range=1-" + imgOrg.getNSlices());
                        ImagePlus imgCrop = WindowManager.getCurrentImage();
                        imgCrop.show();
// substract background                    
                        backgroundSubstract(imgCrop);

                        
// fill outside roi with background except if ROI is a rectangle
                        if (roiType[r] != 0) clearOutside(imgCrop, imgCrop.getRoi());
// save croped image
                        FileSaver imgCrop_save = new FileSaver(imgCrop);
                        if (isStack) 
                            imgCrop_save.saveAsTiffStack(imgOutDir+fileNameWithOutExt+"_Crop"+r+".tif");
                        else 
                            imgCrop_save.saveAsTiff(imgOutDir+fileNameWithOutExt+"_Crop"+r+".tif");
                        if (isStack) 
                            imgCrop.setSlice(2);
                        new WaitForUserDialog("Outline the spheroid").show(); 			// ask for remove spheroid
                        spheroidRoi = imgCrop.getRoi();
// remove spheroid		
                        getCentroid(imgCrop);
// calculate deltaR	                        
                        new WaitForUserDialog("Outline the proliferation").show(); 		// ask for outline proliferation
                        prolifRoi = imgCrop.getRoi();
                        getDeltaArea(imgCrop);



// create image for branch mask
                       ImagePlus imgBranchsMask = imgDup.run(imgCrop,2,imgCrop.getNSlices());
                        

// threshold image                        
                        ImageProcessor ipBranchsMask = imgBranchsMask.getProcessor();
                        RankFilters median = new RankFilters();
                        GaussianBlur blur = new GaussianBlur();
                        if (isStack) {    
                            for (int s = 1; s <= imgBranchsMask.getNSlices(); s++) {
                                imgBranchsMask.setSlice(s);median.rank(ipBranchsMask, 2, RankFilters.MEDIAN);
                                blur.blurGaussian(ipBranchsMask, 1.5, 1.5, 1);
                                ipBranchsMask.setAutoThreshold(AutoThresholder.Method.Triangle, true, 1);
                                double minThreshold = ipBranchsMask.getMinThreshold();
                                double maxThreshold = ipBranchsMask.getMaxThreshold();
                                ipBranchsMask.setThreshold(minThreshold,maxThreshold,1);
                                imgBranchsMask.updateAndDraw();
                                WindowManager.setTempCurrentImage(imgBranchsMask);
                                IJ.run("Convert to Mask");
                            }   
                        }
                        else {
                            median.rank(ipBranchsMask, 2, RankFilters.MEDIAN);
                            blur.blurGaussian(ipBranchsMask, 1.5, 1.5, 1);
                            ipBranchsMask.setAutoThreshold(AutoThresholder.Method.Triangle, true, 1);
                            double minThreshold = ipBranchsMask.getMinThreshold();
                            double maxThreshold = ipBranchsMask.getMaxThreshold();
                            ipBranchsMask.setThreshold(minThreshold,maxThreshold,1);
                            imgBranchsMask.updateAndDraw();
                            WindowManager.setTempCurrentImage(imgBranchsMask);
                            IJ.run("Convert to Mask");
                        }

                        
                        imgBranchsMask.setTitle("Branches_Mask");
                        imgBranchsMask.setSlice(1);
                        imgBranchsMask.show();
                        new WaitForUserDialog("Correct the skeleton mask with paint tools").show();
                        imgBranchsMask.updateAndDraw();
                                                        
// Check if no branches
                        ImageStatistics stats = imgBranchsMask.getStatistics();
                        
                        if (stats.max == stats.min ) { // no branches
                            IJ.log("no branch "+stats.max+","+stats.min);
// write skeleton data with zero
                            outputAnayze.write(fileNameWithOutExt + "\t" + (r+1) + "\t0" + "\t0" + "\t0" + "\t0" + "\t0" + "\t0" + "\t0" + "\t" +
                                     nbNucleus + "\t" + spheroidFeret + "\t" + spheroidArea + "\t" + prolifArea + "\t" + deltaArea + "\n");
                            outputAnayze.flush();                           
// write data in diameter file with zero
                            outputDiameter.write(fileNameWithOutExt + "\t" + (r+1) + "\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\n");
                            outputDiameter.flush();
                            imgBranchsMask.changes = false;
                            imgBranchsMask.close();
                            imgCrop.changes = false;
                            imgCrop.close();
                        }
                        else {    
// Save branches mask
                            FileSaver imgMask_save = new FileSaver(imgBranchsMask);
                            if (imgBranchsMask.getNSlices() > 1) imgMask_save.saveAsTiffStack(imgOutDir+fileNameWithOutExt+"_Crop"+r+"_Mask.tif");
                            else imgMask_save.saveAsTiff(imgOutDir+fileNameWithOutExt+"_Crop"+r+"_Mask.tif");
// find nucleus/spheroid                        
                            if (isStack) {
                                Polygon nucleusCoord = new Polygon();
                                nucleusCoord = findNucleus(imgCrop, imgBranchsMask, imgOutDir, fileNameWithOutExt, r);
                            }
                            imgCrop.close();
                            imgCrop.flush();
// skeletonize
                            ImagePlus imgSkel = imgDup.run(imgBranchsMask,1,imgBranchsMask.getNSlices());
                            Skeletonize3D_ skeleton = new Skeletonize3D_();
                            if (isStack) {
                                for (int s = 1;s <= imgSkel.getNSlices(); s++) {
                                    imgSkel.setSlice(s);
                                    skeleton.setup("",imgSkel);
                                    skeleton.run(imgSkel.getProcessor());
                                    imgSkel.updateAndDraw();
                                }
                            }
                            else {
                                skeleton.setup("",imgSkel);
                                skeleton.run(imgSkel.getProcessor());
                                imgSkel.updateAndDraw();
                            }
                            imgSkel.show();
                            new WaitForUserDialog(" Correct skeleton with paint tools").show();
    // Save corrected skeleton image                        
                            FileSaver imgSkel_save = new FileSaver(imgSkel);
                            if (imgSkel.getNSlices() > 1) imgSkel_save.saveAsTiffStack(imgOutDir+fileNameWithOutExt+"_Crop"+r+"_Skel.tif");
                            else imgSkel_save.saveAsTiff(imgOutDir+fileNameWithOutExt+"_Crop"+r+"_Skel.tif");

                            analyzeSkel(imgSkel,r+1,outputAnayze,fileNameWithOutExt);  

    // compute mean branch diameter
                            double[] meanDiameter = localThickness(imgBranchsMask, imgSkel, imgOutDir,fileNameWithOutExt, r);

                                                 
    // write data in diameter file
                            outputDiameter.write(fileNameWithOutExt + "\t" + (r+1) + "\t");
                            for (int d = 0; d < meanDiameter.length; d++) {
                                outputDiameter.write(meanDiameter[d] + "\t");
                            }
                            outputDiameter.write("\n");
                        }
                    }
                    if (rm.getInstance() != null) rm.close();                    
                    imgOrg.changes = false;
                    imgOrg.close();
                    
                }
            }
           outputAnayze.close();
//           outputCells.close();
           outputDiameter.close();
            IJ.showStatus("End of process");
        } catch (IOException ex) {
            Logger.getLogger(Cytodex_Fluo_Prolif.class.getName()).log(Level.SEVERE, null, ex);
        }
    } 
    
}
