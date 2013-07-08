package edu.cmu.ri.airboat.server;

import java.io.File;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.highgui.Highgui;
import org.opencv.imgproc.Imgproc;
import org.opencv.core.Core;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;

import edu.cmu.ri.crw.VehicleServer;
import edu.cmu.ri.crw.data.Twist;

import android.os.Bundle;
import android.os.IBinder;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceView;
import android.view.WindowManager;

/** Twist { x, y, x, roll, pitch, yaw } */

public class RechargeReturn extends Activity implements CvCameraViewListener2 {
	
	public static final String TAG = RechargeReturn.class.getName();
	Logger log;
	
	private CameraBridgeViewBase mOpenCvCameraView;
	private AirboatService vehicleService = null;
	
	/** The possible view modes of the picture */
	public static final int VIEW_MODE_RGBA = 0;
    public static final int VIEW_MODE_BW = 1;
    public static final int VIEW_MODE_PIC = 2;
    public static final int VIEW_MODE_TEST = 3;
    
    public static int viewMode = VIEW_MODE_TEST;
	
	private MenuItem mItemRGBA;
    private MenuItem mItemBW;
    private MenuItem mItemPic;
    private MenuItem mItemTEST;
    /** End of the view modes */
    
    /** Parameters for controlling the boat */
    private Twist twist;
    private double center = 0;
    private double circle1 = 0, circle2 = 0;
    private int minRadius = 5;
    private int maxRadius = 80;
    private int minDistance = 25;
    
    // For the PD controller
    private double Kp = 2.5;
    private double Kd = 0;
    private double prevDistance = 0;
    
	private double thrust = .2; //.2
	private double angle = 0;
	/** End of parameters for controlling boat */
	
	/** Image processing */
	Mat img, img_hue;
	Mat temp, img_bw1, img_bw2;
	
	private int frameCount = 0;
	boolean drawCircles = false;
	private int fileNum = 1;
	
	private boolean _isBound = false;
	
	/** 
	* Listener that handles changes in connections to the airboat service 
	*/ 
	private ServiceConnection _connection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service) {
	        /** This is called when the connection with the service has been
	    	*   established, giving us the service object we can use to
	    	*   interact with the service. 
	    	* */
	    	vehicleService = ((AirboatService.AirboatBinder)service).getService();
	    }
	
    	public void onServiceDisconnected(ComponentName className) {
    		/** This is called when the connection with the service has been
    		*   unexpectedly disconnected -- that is, its process crashed.
    		*/
	        vehicleService = null;
	    }
	};
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		Log.i(TAG, "called onCreate");
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(R.layout.activity_recharge_return);
		mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.ReturnRechargeCvView);
		mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
		mOpenCvCameraView.setCvCameraViewListener(this);
		
		// create a File object for the parent directory
		File logDirectory = new File("/sdcard/Logs/");
		logDirectory.mkdirs();
		File PicDirectory = new File("/sdcard/TestPics/");
		PicDirectory.mkdirs();
		
		/** Logging used by the class */
		log = Logger.getLogger(RechargeReturn.class.getName());
		try {
			Handler handler = new FileHandler("/sdcard/Logs/RechargeReturn.log", 2000000, 1);
			log.addHandler(handler);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		doBindService();
		
		twist = new Twist();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		/** Inflate the menu; this adds items to the action bar if it is present. */
		mItemRGBA  = menu.add("Normal");
        mItemBW  = menu.add("B&W");
        mItemPic = menu.add("Pic");
        mItemTEST = menu.add("Test");
		getMenuInflater().inflate(R.menu.recharge_return, menu);
		return true;
	}
	
	@Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.i(TAG, "called onOptionsItemSelected; selected item: " + item);
        if (item == mItemRGBA)
            viewMode = VIEW_MODE_RGBA;
        if (item == mItemBW)
            viewMode = VIEW_MODE_BW;
        if (item == mItemPic)
        	viewMode = VIEW_MODE_PIC;
        if (item == mItemTEST)
        	viewMode = VIEW_MODE_TEST;
        return true;
    }
	
	private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
	    @Override
	    public void onManagerConnected(int status) {
	        switch (status) {
	            case LoaderCallbackInterface.SUCCESS:
	            {
	                Log.i(TAG, "OpenCV loaded successfully");
	                mOpenCvCameraView.enableView();
	            } break;
	            default:
	            {
	                super.onManagerConnected(status);
	            } break;
	        }
	    }
	};

	@Override
	public void onResume()
	{
	    super.onResume();
	    OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_5, this, mLoaderCallback);
	}
	
	 @Override
	 public void onPause()
	 {
	     super.onPause();
	     if (mOpenCvCameraView != null)
	         mOpenCvCameraView.disableView();
	 }

	 public void onDestroy() {
		 /** Emergency stop */
	     sendTwist(new Twist(0,0,0,0,0,0));
	     super.onDestroy();
	     doUnbindService();
	     if (mOpenCvCameraView != null)
	         mOpenCvCameraView.disableView();
	 }

	 public void onCameraViewStarted(int width, int height) {
		 /** Finds the center of the frame */
		 center = width/2;
		 
		 img = new Mat();
		 img_hue = new Mat();
		 temp = new Mat();
		 img_bw1 = new Mat();
		 img_bw2 = new Mat();
	 }

	 public void onCameraViewStopped() {
		 if (img != null)
			 img.release();
		 if (img_hue != null)
			 img_hue.release();
		 if (temp != null)
			 temp.release();
		 if (img_bw1 != null)
			 img_bw1.release();
		 if (img_bw2 != null)
			 img_bw2.release();
		 
		 img = null;
		 img_hue = null;
		 temp = null;
		 img_bw1 = null;
		 img_bw2 = null;
	 }

	 public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
		img = inputFrame.rgba();
		
		switch (viewMode) {
		
			case VIEW_MODE_RGBA: /** Detects the circles in the RGB format */
				
				frameCount++;
				drawCircles = false;
				
				/** Setting thrust */
				twist.dx(thrust);
				
				/** Flip image when using on boat */
				//Core.flip(img, img, 0);
				/** Convert it to hue, convert to range color, and blur to remove false circles */
				Imgproc.cvtColor(img, img_hue, Imgproc.COLOR_RGB2HSV);
				img_hue = InRangeCircles(img_hue);
				Imgproc.GaussianBlur(img_hue, img_hue, new Size(9,9), 10, 10 );
				
				/** Create mat for circles and apply the Hough Transform to find the circles */
				Mat circles = new Mat();
				//Imgproc.HoughCircles(img_hue, circles, Imgproc.CV_HOUGH_GRADIENT, 3, minDistance, 100, 50, minRadius, maxRadius ); //Old method
				Imgproc.HoughCircles(img_hue, circles, Imgproc.CV_HOUGH_GRADIENT, 2, minDistance, 70, 25, minRadius, maxRadius );
				
				/** Draws the circles and angle */
				drawCircles(img,circles);
				break;
				
			case VIEW_MODE_BW: /** This mode displays image in black/white to show what the algorithm sees */
				
				Imgproc.cvtColor(img, img_hue, Imgproc.COLOR_RGB2HSV);
				img_hue = InRangeCircles(img_hue);
				Imgproc.GaussianBlur(img_hue, img, new Size(9,9), 10, 10 );
				break;
				
			case VIEW_MODE_PIC: /** Takes pictures every 20 frames */
				
				frameCount++;
				/// Need for normally saving raw photos 
				Imgproc.cvtColor(img, img_hue, Imgproc.COLOR_RGB2BGR);
				Core.flip(img_hue, img_hue, 0);
				if(frameCount%10==0)
				{
					//Imgproc.cvtColor(img, img_hue, Imgproc.COLOR_RGBA2BGR);
					Highgui.imwrite( "/sdcard/TestPics/test"+frameCount/10+".jpg", img_hue );
				}
				break;
				
			case VIEW_MODE_TEST: /** Testing mode for new code */
				
				frameCount++;
				fileNum++;
				if(fileNum>175)
				{
					fileNum = 1;
				}
				Mat temp = Highgui.imread("/sdcard/TestPics/test"+fileNum+".jpg");
				//Mat temp = Highgui.imread("/sdcard/TestPics/test120.jpg"); //120
				Imgproc.cvtColor(temp, img_hue, Imgproc.COLOR_BGR2HSV);
				img_hue = InRangeCircles(img_hue);
				Imgproc.GaussianBlur(img_hue, img_hue, new Size(9,9), 10, 10 );
				/** Create mat for circles and apply the Hough Transform to find the circles */
				Mat circles2 = new Mat();
				Imgproc.HoughCircles(img_hue, circles2, Imgproc.CV_HOUGH_GRADIENT, 2, minDistance, 70, 25, minRadius, maxRadius );
				/** Draws the circles and angle */
				drawCircles(temp,circles2);
				return temp;
				//temp.release();
				//break;
				
			default:
				break;
		}
		
		return img;
	 }
	 
	 private void drawCircles(Mat img, Mat circles)
	 {
		 	logMsg("Found "+ circles.cols() +" circles");
			/** If two circles and the difference in their y axis is less than or equal to 20 */
			if(circles.cols()==2 && Math.abs(circles.get(0,0)[1]-(circles.get(0,1)[1]))<=20)
			{
				drawCircles = true;
				setCircleParameters(circles);
				/// Finds midpoint between the two circles
				double midCircle = (circle1 + circle2)/2;
				/// Substrate from center in order to get negative distance on the left side
				double distance = center - midCircle;
				/// Calculate the speed based on previous distance
				double speed = prevDistance - distance;
				prevDistance = distance;
				/// Calculate angle 
				double tempAngle = Math.atan(distance/img.height());
				/// Get angle from PD controller
				angle = tempAngle * Kp - speed * Kd;
				
				// Ensure angle is within bounds
				if (angle < -1.0)
					angle = -1.0;
				else if (angle > 1.0)
					angle = 1.0;
				
				/** Set the angle of the twist and send twist to server */
				twist.drz(angle);
				sendTwist(twist);
				logMsg("Angle: "+angle);
			} 
			else if(circles.cols()<=1)
			{
				if(minRadius>=10)
					minRadius -= 5;
				if(minDistance>=30)
					minDistance -= 10;
				if(maxRadius<=70)
					maxRadius +=10;
			} else {
				if(minRadius+1<maxRadius)
					minRadius +=5;
				if(maxRadius-10>minRadius)
					maxRadius -=5;
				if(minDistance+10<=100)
					minDistance += 10;
			}
			
			/** If it has not seen the circles in 10 frames something is wrong */
			if(drawCircles)
			{
				frameCount = 0;
			} 
			else if(frameCount>10)
			{
				sendTwist(new Twist(0,0,0,0,0,0));
				frameCount = 0;
				logMsg("Lost the circles");
			}
			
			/** Draw the circles */
			for(int x=0; /* drawCircles==true  &&*/ circles.cols()>1 && x<circles.cols(); x++)
			{
				double circle[] = circles.get(0,x);
				int ptx = (int) Math.round(circle[0]), pty = (int) Math.round(circle[1]);
				Point pt = new Point(ptx, pty);
				int radius = (int)Math.round(circle[2]);
				
				/** Draw the circle outline */
		        Core.circle(img, pt, radius, new Scalar(0,255,0), 3, 8, 0 );
		        /** Draw the circle center */
		        Core.circle(img, pt, 3, new Scalar(0,255,0), -1, 8, 0 );
		        
		        Core.putText(img, ""+ angle, new Point(50,100), Core.FONT_HERSHEY_COMPLEX, .8, new Scalar(255,0,0));
			}
	 }
	 
	 private Mat InRangeCircles(Mat src)
	 {
		 Core.inRange(src, new Scalar(148,40,50), new Scalar(179,255,255), img_bw1);
		 Core.inRange(src, new Scalar(0,40,50), new Scalar(10,255,255), img_bw2);
		 Core.bitwise_or(img_bw1, img_bw2, temp);
		 return temp;
	 }
	 
	 private boolean setCircleParameters(Mat circles)
	 { 
		 /** Determine which circle in on the left and right */
		 if(circles.get(0, 0)[0]<circles.get(0, 1)[0])
		 {
			 circle1 = circles.get(0, 0)[0];
			 circle2 = circles.get(0, 1)[0];
		 } else {
			 circle1 = circles.get(0, 1)[0];
			 circle2 = circles.get(0, 0)[0];
		 }
		 minRadius = (int) ((circles.get(0, 1)[2] + circles.get(0, 0)[2])/2)-10;
		 maxRadius = minRadius + 20;
		 minDistance = (int) (circle2 - circle1 - 10);
		 return true;
	 }

	 private void sendTwist(Twist twist){
		 VehicleServer vehicleServer = vehicleService.getServer();
		 if(vehicleServer != null)
			 vehicleServer.setVelocity(twist);
		 else
			 Log.e(TAG,"Not connected to server");
	 }
	
	 void doBindService() {
	    /** Establish a connection with the service.  We use an explicit
	    *  class name because we want a specific service implementation.
	    * */
	    if (!_isBound) {
	    	bindService(new Intent(this, AirboatService.class), _connection, Context.BIND_AUTO_CREATE);
	    	_isBound = true;
	    }
	 }
	
	 void doUnbindService() {
	    /** Detach our existing connection.  */
    	if (_isBound) {
            unbindService(_connection);
            _isBound = false;
        }
	 }
	 
	 private void logMsg(String msg)
	 {
		 log.info(msg);
	 }
	 
}
