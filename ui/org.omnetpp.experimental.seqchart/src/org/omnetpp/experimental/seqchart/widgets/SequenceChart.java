package org.omnetpp.experimental.seqchart.widgets;


import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.jface.text.DefaultInformationControl;
import org.eclipse.jface.util.SafeRunnable;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.MouseTrackListener;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.omnetpp.experimental.seqchart.editors.EventLogSelection;
import org.omnetpp.experimental.seqchart.editors.IEventLogSelection;
import org.omnetpp.experimental.seqchart.moduletree.ModuleTreeItem;
import org.omnetpp.scave.engine.EventEntry;
import org.omnetpp.scave.engine.EventLog;
import org.omnetpp.scave.engine.IntIntMap;
import org.omnetpp.scave.engine.IntSet;
import org.omnetpp.scave.engine.IntVector;
import org.omnetpp.scave.engine.JavaFriendlyEventLogFacade;
import org.omnetpp.scave.engine.MessageEntry;

/**
 * This is a sequence chart as a single figure.
 *
 * @author andras, levy
 */
//FIXME expressions like int x = (int)(logFacade.getEvent_i_cachedX(i) - getViewportLeft()) may overflow -- make use of XMAX!
//TODO improve mouse handling; support wheel too!
//FIXME ensure consistency of internal data structure when doing set() operations!!!!
//FIXME sometimes there's no tick visible! (axis tick scale calculated wrong?)
//TODO instead of (in addition to) gotoSimulationTime(), we need gotoEvent() as well, which would do vertical scrolling too
//FIXME messages created in initialize() appear to have been created in event #0!!!
//TODO renaming: DELIVERY->SENDING, NONDELIVERY->USAGE, isDelivery->isSending;
//               Timeline modes: Linear, Step, Compact (=nonlinear), Compact2 (CompactWithStep);
//               SortMode to OrderingMode
//TODO cf with ns2 trace file and cEnvir callbacks, and modify file format...
//TODO double-click on msg arrow: filter?
//TODO proper "hand" cursor - current one is not very intuitive
//TODO when switching timeline mode: tweak zoom so that it displays the same interval!!!!
//FIXME in some rare cases, arrow head is not shown! when ellipse is exactly a half circle? (see nclients.log, nonlinear axis)
//TODO max number of event selection marks must be limited (e.g. max 1000)
//FIXME auto-turn-off message names and arrowheads when there're too many messages?
public class SequenceChart extends CachingCanvas implements ISelectionProvider {

	private static final Color LABEL_COLOR = new Color(null, 0, 0, 0);
	private static final Color AXIS_COLOR = new Color(null, 120, 120, 120);
	private static final Color EVENT_FG_COLOR = new Color(null,255,0,0);
	private static final Color EVENT_BG_COLOR = new Color(null,255,255,255);
	private static final Color EVENT_SEL_COLOR = new Color(null,255,0,0);
	private static final Color ARROWHEAD_COLOR = null; // defaults to line color
	private static final Color MESSAGE_LABEL_COLOR = null; // defaults to line color
	private static final Color DELIVERY_MESSAGE_COLOR = new Color(null,0,0,255);
	private static final Color NONDELIVERY_MESSAGE_COLOR = new Color(null,0,150,0);
	private static final Color TICKS_LINE_COLOR = new Color(null, 240, 240, 240);
	private static final Cursor DRAGCURSOR = new Cursor(null, SWT.CURSOR_SIZEALL);
	private static final int[] DOTTED_LINE_PATTERN = new int[] {2,2}; // 2px black, 2px gap
	
	private static final int XMAX = 10000;
	private static final int MAX_TOOLTIP_LINES = 30;
	private static final int ANTIALIAS_TURN_ON_AT_MSEC = 100;
	private static final int ANTIALIAS_TURN_OFF_AT_MSEC = 300;
	private static final int MOUSE_TOLERANCE = 1;

	private static final int DELIVERY_SELFARROW_HEIGHT = 20; // vertical radius of ellipse for selfmsg arrows
	private static final int NONDELIVERY_SELFARROW_HEIGHT = 10; // same for non-delivery messages
	private static final int ARROWHEAD_LENGTH = 10; // length of message arrow head
	private static final int ARROWHEAD_WIDTH = 7; // width of message arrow head
	private static final int AXISLABEL_DISTANCE = 15; // distance of timeline label above axis
	private static final int EVENT_SEL_RADIUS = 10; // radius of event selection mark circle
	private static final int TICK_LABEL_WIDTH = 50; // minimum tick label width reserved
	private static final int CLIPRECT_BORDER = 10; // should be greater than an arrowhead or event "ball" radius
	
	protected EventLog eventLog; // contains the data to be displayed
	protected JavaFriendlyEventLogFacade logFacade; // helpful facade on eventlog
	
	protected double pixelsPerTimelineUnit = 1;
	protected int tickScale = 1; // -1 means step=0.1, in power of timeline units
	private boolean antiAlias = true;  // antialiasing -- this gets turned on/off automatically
	private boolean showArrowHeads = true; // whether arrow heads are drawn or not
	private int axisOffset = 50;  // y coord of first axis
	private int axisSpacing = 50; // y distance between two axes

	private boolean showMessageNames;
	private boolean showNonDeliveryMessages; // show or hide non-delivery message arrows
	private boolean showEventNumbers;
	private TimelineMode timelineMode = TimelineMode.LINEAR; // specifies timeline x coordinate transformation, see enum
	private TimelineSortMode timelineSortMode = TimelineSortMode.MODULE_ID; // specifies the ordering mode of timelines
	private double nonLinearFocus = 1; // parameter for non-linear timeline transformation
	
	private DefaultInformationControl tooltipWidget; // the current tooltip (Note: SWT's Tooltip cannot be used as it wraps lines)
	
	private int dragStartX, dragStartY; // temporary variables for drag handling
	private List<ModuleTreeItem> axisModules; // the modules which should have an axis (they must be part of a module tree!)
	private Integer[] axisModulePositions; // y order of the axis modules (in the same order as axisModules); this is a permutation of the 0..axisModule.size()-1 numbers
	private IntSet moduleIds; // calculated from axisModules: module Ids of all modules which are submodule of an axisModule (i.e. whose events appear on the chart)

	private ArrayList<BigDecimal> ticks = new ArrayList<BigDecimal>(); // a list of tick simulation times to be drawn on axis
	
	private ArrayList<SelectionListener> selectionListenerList = new ArrayList<SelectionListener>(); // SWT selection listeners

	private List<EventEntry> selectionEvents = new ArrayList<EventEntry>(); // the selection
    private ListenerList selectionChangedListeners = new ListenerList(); // list of selection change listeners (type ISelectionChangedListener).

	private static Rectangle TEMP_RECT = new Rectangle();  // tmp var for local calculations (a second Rectangle.SINGLETON)
    
    
	public enum TimelineMode {
		LINEAR,
		STEP,
		NON_LINEAR
	}

	public enum TimelineSortMode {
		MANUAL,
		MODULE_ID,
		MODULE_NAME,
		MINIMIZE_CROSSINGS,
		MINIMIZE_CROSSINGS_HIERARCHICALLY
	}
	
	/**
	 * This class is for optimizing drawing when the chart is zoomed out and
	 * there's a large number of connection arrows on top of each other.
	 * Most arrows tend to be vertical then, so we only need to bother with
	 * drawing vertical lines if it sets new pixels over previously drawn ones
	 * at that x coordinate. We exploit that x coordinates grow monotonously.  
	 */
	static class VLineBuffer {
		int currentX = -1;
		static class Region {
			int y1, y2; 
			Region() {} 
			Region(int y1, int y2) {this.y1=y1; this.y2=y2;} 
		}
		ArrayList<Region> regions = new ArrayList<Region>();

		public boolean vlineContainsNewPixel(int x, int y1, int y2) {
			if (y1>y2) {
				int tmp = y1;
				y1 = y2;
				y2 = tmp;
			}
			if (x!=currentX) {
				// start new X
				Region r = regions.isEmpty() ? new Region() : regions.get(0);
				regions.clear();
				r.y1 = y1;
				r.y2 = y2;
				regions.add(r);
				currentX = x;
				return true;
			}

			// find an overlapping region
			int i = findOverlappingRegion(y1, y2);
			if (i==-1) {
				// no overlapping region, add this one and return
				regions.add(new Region(y1, y2));
				return true;
			}

			// existing region entirely contains this one (most frequent, fast route)
			Region r = regions.get(i);
			if (y1>=r.y1 && y2<=r.y2)
				return false;

			// merge it into other regions
			mergeRegion(new Region(y1, y2));
			return true;
		}
		private void mergeRegion(Region r) {
			// merge all regions into r, then add it back
			int i;
			while ((i = findOverlappingRegion(r.y1, r.y2)) != -1) {
				Region r2 = regions.remove(i);
				if (r.y1>r2.y1) r.y1 = r2.y1; 
				if (r.y2<r2.y2) r.y2 = r2.y2;
			}
			regions.add(r);
		}
		private int findOverlappingRegion(int y1, int y2) {
			for (int i=0; i<regions.size(); i++) {
				Region r = regions.get(i);
				if (r.y1 < y2 && r.y2 > y1)
					return i;
			}
			return -1;
		}
	}
	
	/**
     * Constructor.
     */
	public SequenceChart(Composite parent, int style) {
		super(parent, style);
    	setUpMouseHandling();
	}

	/**
	 * Returns chart scale, that is, the number of pixels a "timeline unit" maps to.
     *
	 * The meaning of "timeline unit" depends on the timeline mode (see enum TimelineMode).
	 * For LINEAR mode it is <i>second</i> (simulation time), for STEP mode it is <i>event</i>,
	 * and for NON_LINEAR mode it is calculated as a nonlinear function of simulation time.
	 */
	public double getPixelsPerTimelineUnit() {
		return pixelsPerTimelineUnit;	
	}
	
	/**
	 * Set chart scale (number of pixels a "timeline unit" maps to), 
	 * and adjusts the density of ticks. 
	 */
	public void setPixelsPerTimelineUnit(double pp) {
		if (pixelsPerTimelineUnit == pp)
			 return; // already set, nothing to do
		
		//XXX limit to a number where 64-bit longs won't overflow 
		
		// set pixels per sec, and recalculate tick spacing
		if (pp <= 0)
			pp = 1e-12;
		pixelsPerTimelineUnit = pp;
		tickScale = (int)Math.ceil(Math.log10(TICK_LABEL_WIDTH / pp));

		System.out.println("pixels per timeline unit="+pixelsPerTimelineUnit+"  tickScale="+tickScale);
	}
	
	/**
	 * Returns the pixel distance between adjacent axes in the chart.
	 */
	public int getAxisSpacing() {
		return axisSpacing;
	}

	/**
	 * Sets the pixel distance between adjacent axes in the chart.
	 */
	public void setAxisSpacing(int axisSpacing) {
		this.axisSpacing = axisSpacing>0 ? axisSpacing : 1;
		recalculateVirtualSize();
		clearCanvasCacheAndRedraw();
	}

	/**
	 * Hide/show message names on the arrows.
	 */
	public void setShowMessageNames(boolean showMessageNames) {
		this.showMessageNames = showMessageNames;
		clearCanvasCacheAndRedraw();
	}

	/**
	 * Returns whether message names are displayed on the arrows.
	 */
	public boolean getShowMessageNames() {
		return showMessageNames;
	}

	/**
	 * Shows/Hides non-delivery messages.
	 */
	public void setShowNonDeliveryMessages(boolean showNonDeliveryMessages) {
		this.showNonDeliveryMessages = showNonDeliveryMessages;
		clearCanvasCacheAndRedraw();
	}
	
	/**
	 * Returns whether non-delivery messages are shown in the chart.
	 */
	public boolean getShowNonDeliveryMessages() {
		return showNonDeliveryMessages;
	}

	/**
	 * Shows/Hides event numbers.
	 */
	public void setShowEventNumbers(boolean showEventNumbers) {
		this.showEventNumbers = showEventNumbers;
		clearCanvasCacheAndRedraw();
	}

	/**
	 * Returns whether event numbers are shown in the chart.
	 */
	public boolean getShowEventNumbers() {
		return showEventNumbers;
	}
	
	/**
	 * Shows/hides arrow heads.
	 */
	public boolean getShowArrowHeads() {
		return showArrowHeads;
	}

	/**
	 * Returns whether arrow heads are shown in the chart.
	 */
	public void setShowArrowHeads(boolean drawArrowHeads) {
		this.showArrowHeads = drawArrowHeads;
		clearCanvasCacheAndRedraw();
	}

	/**
	 * Changes the timeline mode and updates figure accordingly.
	 * Tries to show the current simulation time after changing the timeline coordinate system.
	 */
	public void setTimelineMode(TimelineMode timelineMode) {
		saveViewportSimulationTimeRange();
		this.timelineMode = timelineMode;
		recalculateTimelineCoordinates();
		//setPixelsPerTimelineUnit(suggestPixelsPerTimelineUnit());
		restoreViewportSimulationTimeRange();
	}

	/**
	 * Returns the current timeline mode.
	 */
	public TimelineMode getTimelineMode() {
		return timelineMode;
	}
	
	/**
	 * Changes the timeline sort mode and updates figure accordingly.
	 */
	public void setTimelineSortMode(TimelineSortMode timelineSortMode) {
		this.timelineSortMode = timelineSortMode;
		calculateAxisYs();
		updateFigure();
		clearCanvasCacheAndRedraw();
	}
	
	/**
	 * Return the current timeline sort mode.
	 */
	public TimelineSortMode getTimelineSortMode() {
		return timelineSortMode;
	}
	
	// TODO:
	double viewportLeftSimulationTime;
	double viewportRightSimulationTime;
	
	/**
	 * TODO:
	 */
	public void saveViewportSimulationTimeRange()
	{
		viewportLeftSimulationTime = pixelToSimulationTime(0);
		viewportRightSimulationTime = pixelToSimulationTime(getWidth());
	}
	
	/**
	 * TODO:
	 */
	public void restoreViewportSimulationTimeRange()
	{
		double timelineUnitDelta = (simulationTimeToTimelineCoordinate(viewportRightSimulationTime)) - (simulationTimeToTimelineCoordinate(viewportLeftSimulationTime));
		setPixelsPerTimelineUnit(getWidth() / timelineUnitDelta);
		scrollHorizontalTo(getViewportLeft() + simulationTimeToPixel(viewportLeftSimulationTime));
		recalculateVirtualSize();
	}

	/**
	 * Returns the simulation time of the canvas's center.
	 */
	public double currentSimulationTime() {
		int middleX = getWidth()/2;
		return pixelToSimulationTime(middleX);
	}
	
	/**
	 * Scroll the canvas so as to make the simulation time visible 
	 */
	public void gotoSimulationTime(double time) {
		double xDouble = simulationTimeToTimelineCoordinate(time) * pixelsPerTimelineUnit;
		long x = xDouble < 0 ? 0 : xDouble>Long.MAX_VALUE ? Long.MAX_VALUE : (long)xDouble;
		scrollHorizontalTo(x - getWidth()/2);
		redraw();
	}

	/**
	 * Updates the figure, recalculates timeline coordinates, canvas size.
	 */
	public void updateFigure() {
		// transform simulation times to timeline coordinate system
		recalculateTimelineCoordinates();
		// adapt our zoom level to the current eventLog
		setPixelsPerTimelineUnit(suggestPixelsPerTimelineUnit());
		recalculateVirtualSize();
		// notify listeners
		fireSelectionChanged();
	}
	
	/**
	 * Updates the figure with the given log and axis modules.
	 * Scrolls canvas to current simulation time after updating.
	 */
	public void updateFigure(EventLog eventLog, ArrayList<ModuleTreeItem> axisModules) {
		double time = currentSimulationTime();
		setEventLog(eventLog);
		setAxisModules(axisModules);
		updateFigure();
		gotoSimulationTime(time);
	}

	/**
	 * Increases pixels per timeline coordinate.
	 */
	public void zoomIn() {
		zoomBy(1.5);
	}

	/**
	 * Decreases pixels per timeline coordinate. 
	 */
	public void zoomOut() {
		zoomBy(1.0 / 1.5);
	}

	/**
	 * Changes pixel per timeline coordinate by zoomFactor.
	 */
	public void zoomBy(double zoomFactor) {
		double time = currentSimulationTime();
		setPixelsPerTimelineUnit(getPixelsPerTimelineUnit() * zoomFactor);	
		recalculateVirtualSize();
		gotoSimulationTime(time);
	}
	
	/**
	 * The event log (data) to be displayed in the chart
	 */
	public EventLog getEventLog() {
		return eventLog;
	}

	/**
	 * Set the event log to be displayed in the chart
	 */
	public void setEventLog(EventLog eventLog) {
		this.eventLog = eventLog;
		this.logFacade = new JavaFriendlyEventLogFacade(eventLog);
		
		//XXX what about resetting dependent state:
		//clearCanvasCacheAndRedraw();
		//axisModules = null;
		//axisModulePositions = null;
		//moduleIds = null;
		//selectionEvents = null;

		//XXX also update eventlog's cached vars:
		//recalculateTimelineCoordinates(), etc
	}
	
	/**
	 * Sets which modules should have axes. Items in axisModules
	 * should point to elements in the moduleTree. 
	 */
	public void setAxisModules(ArrayList<ModuleTreeItem> axisModules) {
		this.axisModules = axisModules;
		
		// update moduleIds
		moduleIds = new IntSet();
		for (int i=0; i<axisModules.size(); i++) {
			ModuleTreeItem treeItem = axisModules.get(i);
			// propagate y to all submodules recursively
			treeItem.visitLeaves(new ModuleTreeItem.IModuleTreeItemVisitor() {
				public void visit(ModuleTreeItem treeItem) {
					moduleIds.insert(treeItem.getModuleId());
				}
			});
		}
		
		calculateAxisYs();

		//FIXME what about updating the chart?
	}
	
	/**
	 * Calculates Y coordinates of axis sorting by module ids.
	 */
	private void sortTimelinesByModuleId()
	{
		Integer[] axisModulesIndices = new Integer[axisModules.size()];
		
		for (int i = 0; i < axisModulesIndices.length; i++)
			axisModulesIndices[i] = i;
		
		java.util.Arrays.sort(axisModulesIndices, new java.util.Comparator<Integer>() {
				public int compare(Integer o1, Integer o2) {
					return ((Integer)axisModules.get(o1).getModuleId()).compareTo(axisModules.get(o2).getModuleId());
				}
			});
		
		for (int i = 0; i < axisModulesIndices.length; i++)
			axisModulePositions[axisModulesIndices[i]] = i;
	}
	
	/**
	 * Calculates Y coordinates of axis sorting by module names.
	 */
	private void sortTimelinesByModuleName()
	{
		Integer[] axisModulesIndexes = new Integer[axisModules.size()];
		
		for (int i = 0; i < axisModulesIndexes.length; i++)
			axisModulesIndexes[i] = i;
		
		java.util.Arrays.sort(axisModulesIndexes, new java.util.Comparator<Integer>() {
				public int compare(Integer o1, Integer o2) {
					return axisModules.get(o1).getModuleFullPath().compareTo(axisModules.get(o2).getModuleFullPath());
				}
			});
		
		for (int i = 0; i < axisModulesIndexes.length; i++)
			axisModulePositions[axisModulesIndexes[i]] = i;
	}

	/**
	 * Calculates Y coordinates of axis by minimizing message arrows crossing timelines.
	 * A message arrow costs as much as many axis it crosses. Uses simulated annealing.
	 */
	private void sortTimelinesByMinimizingCost(IntVector axisMatrix)
	{
		int cycleCount = 0;
		int noMoveCount = 0;
		int noRandomMoveCount = 0;
		int numberOfAxis = axisModules.size();
		int[] axisPositions = new int[numberOfAxis]; // actual positions of axis to be returned
		int[] candidateAxisPositions = new int[numberOfAxis]; // new positions of axis to be set (if better)
		int[] bestAxisPositions = new int[numberOfAxis]; // best positions choosen from a set of candidates
		int[] possibleNewPositionsForSelectedAxis = new int[numberOfAxis]; // a list of possible new positions for a selected axis
		Random r = new Random(0);
		double temperature = 5.0;

		// set initial axis positions 
		sortTimelinesByModuleName();
		for (int i = 0; i < numberOfAxis; i++)
			axisPositions[i] = axisModulePositions[i];
		
		while (cycleCount < 100 && (noMoveCount < numberOfAxis || noRandomMoveCount < numberOfAxis))
		{
			cycleCount++;
			
			// randomly choose an axis which we move to the best place (there are numberOfAxis possibilities)
			//int selectedAxisIndex = r.nextInt(numberOfAxis);
			int selectedAxisIndex = cycleCount % numberOfAxis;
			ModuleTreeItem selectedAxisModule = axisModules.get(selectedAxisIndex);
			int bestPositionOfSelectedAxis = -1;
			int costOfBestPositions = Integer.MAX_VALUE;
			
			// find out possible new positions for selected axis
			for (int newPositionCandidate = 0; newPositionCandidate < numberOfAxis; newPositionCandidate++) {
				possibleNewPositionsForSelectedAxis[newPositionCandidate] = newPositionCandidate;

				// do not allow to move axis to a place where none of its neighbour axis have the same
				// parent module in hierarhical mode
				if (timelineSortMode == TimelineSortMode.MINIMIZE_CROSSINGS_HIERARCHICALLY)
				{
					ModuleTreeItem previousAxisModule = null;
					ModuleTreeItem nextAxisModule = null;
					
					// find axis module that would be right before the selected axis module at new position
					if (newPositionCandidate > 0)
						for (int i = 0; i < numberOfAxis; i++)
							if (axisPositions[i] == newPositionCandidate - 1) {
								previousAxisModule = axisModules.get(i);
								break;
							}

					// find axis module that would be right after the selected axis module at new position
					if (newPositionCandidate < numberOfAxis - 1)
						for (int i = 0; i < numberOfAxis; i++)
							if (axisPositions[i] == newPositionCandidate) {
								nextAxisModule = axisModules.get(i);
								break;
							}

					if ((previousAxisModule == null || previousAxisModule.getParentModule() != selectedAxisModule.getParentModule()) &&
					    (nextAxisModule == null || nextAxisModule.getParentModule() != selectedAxisModule.getParentModule()))
						possibleNewPositionsForSelectedAxis[newPositionCandidate] = -1;
				}
			}

			// assume moving axis at index to position i while keeping the order of others and calculate cost
			for (int newPositionOfSelectedAxis : possibleNewPositionsForSelectedAxis) {
				if (newPositionOfSelectedAxis == -1)
					continue;

				int cost = 0;
				
				// set up candidateAxisPositions so that the order of other axis do not change
				for (int i = 0; i < numberOfAxis; i++) {
					int pos = axisPositions[i];
					if (newPositionOfSelectedAxis <= pos && pos < axisPositions[selectedAxisIndex])
						pos++;
					if (axisPositions[selectedAxisIndex] < pos && pos <= newPositionOfSelectedAxis)
						pos--;
					candidateAxisPositions[i] = pos;
				}
				candidateAxisPositions[selectedAxisIndex] = newPositionOfSelectedAxis;

				// sum up cost of messages to other axis
				for (int i = 0; i < numberOfAxis; i++)
					for (int j = 0; j < numberOfAxis; j++)
						cost += Math.abs(candidateAxisPositions[i] - candidateAxisPositions[j]) *
								(axisMatrix.get(numberOfAxis * i + j) +
								 axisMatrix.get(numberOfAxis * j + i));
				
				// find minimum cost
				if (cost < costOfBestPositions) {
					costOfBestPositions = cost;
					bestPositionOfSelectedAxis = newPositionOfSelectedAxis;
					System.arraycopy(candidateAxisPositions, 0, bestAxisPositions, 0, numberOfAxis);
				}
			}
			
			// move selected axis into best position if applicable
			if (bestPositionOfSelectedAxis != -1 && selectedAxisIndex != bestPositionOfSelectedAxis) {
				System.arraycopy(bestAxisPositions, 0, axisPositions, 0, numberOfAxis);
				noMoveCount = 0;
			}
			else
				noMoveCount++;

			// randomly swap axis based on temperature
			double t = temperature;
			noRandomMoveCount++;
			while (false && r.nextDouble() < t) {
				int i1 = r.nextInt(numberOfAxis);
				int i2 = r.nextInt(numberOfAxis);
				int i = axisPositions[i1];
				axisPositions[i1] = axisPositions[i2];
				axisPositions[i2] = i;
				noRandomMoveCount = 0;
				t--;
			}
			temperature *= 0.9;
		}

		for (int i = 0; i < numberOfAxis; i++)
			axisModulePositions[i] = axisPositions[i];
	}
	
	/**
	 * Sorts axis modules minimizing the number of crosses between timelines and messages arrows.
	 */
	private void calculateAxisYs()
	{
		this.axisModulePositions = new Integer[axisModules.size()];

		switch (timelineSortMode) {
			case MODULE_ID:
				sortTimelinesByModuleId();
				break;
			case MANUAL:
				break;
			case MODULE_NAME:
				sortTimelinesByModuleName();
				break;
			case MINIMIZE_CROSSINGS:
			case MINIMIZE_CROSSINGS_HIERARCHICALLY:
				// build module id to axis map
				final IntIntMap moduleIdToAxisIdMap = new IntIntMap();
				for (int i=0; i<axisModules.size(); i++) {
					final Integer ii = i;
					ModuleTreeItem treeItem = axisModules.get(i);
					treeItem.visitLeaves(new ModuleTreeItem.IModuleTreeItemVisitor() {
						public void visit(ModuleTreeItem treeItem) {
							moduleIdToAxisIdMap.set(treeItem.getModuleId(), ii);
						}
					});
				}

				sortTimelinesByMinimizingCost(eventLog.buildMessageCountGraph(moduleIdToAxisIdMap));
				break;
		}
	}
	
	/**
	 * Calculates (x,y) coordinates for all events, based on axes settings and timeline coordinates
	 */
	private void recalculateEventCoordinates() {
		// different y for each selected module
		final HashMap<Integer, Integer> moduleIdToAxisYMap = new HashMap<Integer, Integer>();
		for (int i=0; i<axisModules.size(); i++) {
			ModuleTreeItem treeItem = axisModules.get(i);
			final int y = getAxisY(i);
			// propagate y to all submodules recursively
			treeItem.visitLeaves(new ModuleTreeItem.IModuleTreeItemVisitor() {
				public void visit(ModuleTreeItem treeItem) {
					moduleIdToAxisYMap.put(treeItem.getModuleId(), y);
				}
			});
		}
		
        for (int i=0; i<logFacade.getNumEvents(); i++) {
			long x = Math.round(logFacade.getEvent_i_timelineCoordinate(i) * pixelsPerTimelineUnit);
			long y = moduleIdToAxisYMap.get(logFacade.getEvent_i_module_moduleId(i));
			logFacade.setEvent_cachedX(i, x);
			logFacade.setEvent_cachedY(i, y);
        }
		
	}
	
	/**
	 * Adds an SWT selection listener which gets notified when the widget
	 * is clicked or double-clicked.
	 */
	public void addSelectionListener (SelectionListener listener) {
		selectionListenerList.add(listener);
	}

	/**
	 * Removes the given SWT selection listener.
	 */
	public void removeSelectionListener (SelectionListener listener) {
		selectionListenerList.remove(listener);
	}

	protected void fireSelection(boolean defaultSelection) {
		Event event = new Event();
		event.display = getDisplay();
		event.widget = this;
		SelectionEvent se = new SelectionEvent(event);
		for (SelectionListener listener : selectionListenerList) {
			if (defaultSelection)
				listener.widgetDefaultSelected(se);
			else
				listener.widgetSelected(se);
		}
	}
	
	/**
	 * If the current pixels/sec setting doesn't look useful for the current event log,
	 * suggest a better one. Otherwise just returns the old value. The current settings
	 * are not changed.
	 */
	public double suggestPixelsPerTimelineUnit() {
		// adjust pixelsPerTimelineUnit if it's way out of the range that makes sense
		int numEvents = eventLog.getNumEvents();
		if (numEvents>=2) {
			double tStart = eventLog.getFirstEvent().getTimelineCoordinate();
			double tEnd = eventLog.getEvent(numEvents-1).getTimelineCoordinate();
			double eventPerSec = numEvents / (tEnd - tStart);

			int chartWidthPixels = getWidth();
			if (chartWidthPixels<=0) chartWidthPixels = 800;  // may be 0 on startup

			double minPixelsPerTimelineUnit = eventPerSec * 10;  // we want at least 10 pixel/event
			double maxPixelsPerTimelineUnit = eventPerSec * (chartWidthPixels/10);  // we want at least 10 events on the chart

			if (pixelsPerTimelineUnit < minPixelsPerTimelineUnit)
				return minPixelsPerTimelineUnit;
			else if (pixelsPerTimelineUnit > maxPixelsPerTimelineUnit)
				return maxPixelsPerTimelineUnit;
		}
		return pixelsPerTimelineUnit; // the current setting is fine
	}

	private void recalculateVirtualSize() {
		EventEntry lastEvent = eventLog.getLastEvent();
		long width = lastEvent==null ? 0 : (long)(lastEvent.getTimelineCoordinate() * getPixelsPerTimelineUnit()) + 3; // event mark should fit in
		width = Math.max(width, 600); // at least half a screen
		long height = axisModules.size() * axisSpacing + axisOffset * 2;
		setVirtualSize(width, height);
		recalculateEventCoordinates();  //XXX add this to other places too where something changes
		clearCanvasCacheAndRedraw();
	}

	public void clearCanvasCacheAndRedraw() {
		clearCanvasCache();
		redraw();
	}

	@Override
	protected void beforePaint() {
	}
	
	@Override
	protected void paintCachableLayer(Graphics graphics) {
		paintFigure(graphics);
	}

	@Override
	protected void paintNoncachableLayer(Graphics graphics) {
		paintAxisLabels(graphics);
        paintEventSelectionMarks(graphics);
        paintTicks(graphics);
	}

	/**
	 * Utility function to determine event range we need to paint. 
	 * Returns an array of size 2, or null if the eventLog is empty.
	 */
	protected int[] getFirstLastEventIndicesInRange(int x1, int x2) {
		if (eventLog.getNumEvents()==0)
			return null;
		
		x1 -= CLIPRECT_BORDER;
		x2 += CLIPRECT_BORDER; // so that if an arrowhead or event "ball" extends into the cliprect, it gets redrawn
		double tleft = pixelToTimelineCoordinate(x1);
		double tright = pixelToTimelineCoordinate(x2);
		EventEntry startEvent = eventLog.getLastEventBeforeByTimelineCoordinate(tleft);
		if (startEvent==null)
			startEvent = eventLog.getFirstEvent();
		EventEntry endEvent = eventLog.getFirstEventAfterByTimelineCoordinate(tright);
		if (endEvent==null)
			endEvent = eventLog.getLastEvent();
		int startEventIndex = (startEvent!=null) ? eventLog.findEvent(startEvent) : 0;
		int endEventIndex = (endEvent!=null) ? eventLog.findEvent(endEvent) : eventLog.getNumEvents(); 
		return new int[] {startEventIndex, endEventIndex};
	}	

	protected void paintFigure(Graphics graphics) {
		if (eventLog!=null && eventLog.getNumEvents()>0) {
			long startMillis = System.currentTimeMillis();

			graphics.setAntialias(antiAlias ? SWT.ON : SWT.OFF);
			graphics.setTextAntialias(SWT.ON);

			calculateTicks(graphics);
			
			for (int i=0; i<axisModules.size(); i++) {
				int y = (int)(getAxisY(i) - getViewportTop());
				drawAxis(graphics, y);
			}

			Rectangle clip = graphics.getClip(new Rectangle());
			int[] eventIndexRange = getFirstLastEventIndicesInRange(clip.x, clip.right());
			int startEventIndex = eventIndexRange[0];
			int endEventIndex = eventIndexRange[1];
	        System.out.println("redrawing events (index) from: " + startEventIndex + " to: " + endEventIndex);
			int startEventNumber = logFacade.getEvent_i_eventNumber(startEventIndex);
			int endEventNumber = logFacade.getEvent_i_eventNumber(endEventIndex);
			
	        // paint arrows
	        IntVector msgIndices = eventLog.getMessagesIntersecting(startEventNumber, endEventNumber, moduleIds, showNonDeliveryMessages); 
	        //System.out.println(""+msgIndices.size()+" msgs to draw");
	        VLineBuffer vlineBuffer = new VLineBuffer();
	        for (int i=0; i<msgIndices.size(); i++) {
	        	int pos = msgIndices.get(i);
	            drawMessageArrow(graphics, pos, vlineBuffer);
	        }
	        msgIndices.delete();
	        //System.out.println("draw msgs: "+(System.currentTimeMillis()-startMillis)+"ms");
	       
			// paint events
	        graphics.setForegroundColor(EVENT_FG_COLOR);
	        HashMap<Integer,Integer> axisYtoLastX = new HashMap<Integer, Integer>();
	        for (int i=startEventIndex; i<=endEventIndex; i++) {
				int x = (int)(logFacade.getEvent_i_cachedX(i) - getViewportLeft());
				int y = (int)(logFacade.getEvent_i_cachedY(i) - getViewportTop());

				// performance optimization: don't paint event if there's one already drawn exactly there
				if (!Integer.valueOf(x).equals(axisYtoLastX.get(y))) {
					axisYtoLastX.put(y,x);
					
					graphics.setBackgroundColor(EVENT_FG_COLOR);
					graphics.fillOval(x-2, y-3, 5, 7);
	
					if (showEventNumbers) {
						graphics.setBackgroundColor(EVENT_BG_COLOR);
						graphics.fillText("#"+logFacade.getEvent_i_eventNumber(i), x-10, y - AXISLABEL_DISTANCE);
					}
				}
	        }           	
	
	        // turn on/off anti-alias 
	        long repaintMillis = System.currentTimeMillis()-startMillis;
	        System.out.println("redraw(): "+repaintMillis+"ms");
	        if (antiAlias && repaintMillis > ANTIALIAS_TURN_OFF_AT_MSEC)
	        	antiAlias = false;
	        else if (!antiAlias && repaintMillis < ANTIALIAS_TURN_ON_AT_MSEC)
	        	antiAlias = true;
	        //XXX also: turn it off also during painting if it's going to take too long 
		}
	}

	private void paintTicks(Graphics graphics) {
		for (BigDecimal tick : ticks)
		{
			int x = simulationTimeToPixel(tick.doubleValue());
			graphics.setForegroundColor(TICKS_LINE_COLOR);
			graphics.drawLine(x, 0, x, getHeight());
			graphics.drawText(tick.toPlainString() + "s", x, 3);
			graphics.drawText(tick.toPlainString() + "s", x, getHeight() - 33);
		}
	}
	
	private void paintEventSelectionMarks(Graphics graphics) {
		int[] eventIndexRange = getFirstLastEventIndicesInRange(0, getClientArea().width);
		int startEventIndex = eventIndexRange[0];
		int endEventIndex = eventIndexRange[1];
		int startEventNumber = logFacade.getEvent_i_eventNumber(startEventIndex);
		int endEventNumber = logFacade.getEvent_i_eventNumber(endEventIndex);
		
		// paint event selection marks
		if (selectionEvents != null) {
			graphics.setLineStyle(SWT.LINE_SOLID);
		    graphics.setForegroundColor(EVENT_SEL_COLOR);
			for (EventEntry sel : selectionEvents) {
		    	if (startEventNumber<=sel.getEventNumber() && sel.getEventNumber()<=endEventNumber)
		    	{
		    		int x = (int)(sel.getCachedX()-getViewportLeft());
		    		int y = (int)(sel.getCachedY()-getViewportTop());
		    		graphics.drawOval(x - EVENT_SEL_RADIUS, y - EVENT_SEL_RADIUS, EVENT_SEL_RADIUS * 2 + 1, EVENT_SEL_RADIUS * 2 + 1);
		    	}
			}
		}
	}

	private void drawMessageArrow(Graphics graphics, int pos, VLineBuffer vlineBuffer) {
        int x1 = (int)(logFacade.getMessage_source_cachedX(pos) - getViewportLeft());
        int y1 = (int)(logFacade.getMessage_source_cachedY(pos) - getViewportTop());
        int x2 = (int)(logFacade.getMessage_target_cachedX(pos) - getViewportLeft());
        int y2 = (int)(logFacade.getMessage_target_cachedY(pos) - getViewportTop());
		//System.out.printf("drawing %d %d %d %d \n", x1, x2, y1, y2);

        // check whether we'll need to draw an arrowhead
        boolean needArrowHead = showArrowHeads;
		if (needArrowHead) {
			// optimization: check if arrowhead is in the clipping rect (don't draw it if not)
			TEMP_RECT.setLocation(x2,y2); 
			TEMP_RECT.expand(2*ARROWHEAD_LENGTH, 2*ARROWHEAD_LENGTH);
			graphics.getClip(Rectangle.SINGLETON);
			needArrowHead = Rectangle.SINGLETON.intersects(TEMP_RECT);
		}
		
		// message name (as label on the arrow)
		String arrowLabel = null;
		if (showMessageNames)
			arrowLabel = logFacade.getMessage_messageName(pos);

		// line color and style depends on message type
		boolean isDelivery = logFacade.getMessage_isDelivery(pos);
		if (isDelivery) {
			graphics.setForegroundColor(DELIVERY_MESSAGE_COLOR);
			graphics.setLineStyle(SWT.LINE_SOLID);
		}
		else { 
			graphics.setForegroundColor(NONDELIVERY_MESSAGE_COLOR);
			graphics.setLineDash(DOTTED_LINE_PATTERN); // SWT.LINE_DOT style is not what we want
		}

		// check if message was sent from a method call (event module != message source module).
		// XXX This currently only works for non-delivery messages, as we don't have enough info in the log file;
		// XXX even with non-delivery messages it acts strange... 
		//if (!isDelivery && logFacade.getMessage_source_cause_module_moduleId(pos) != logFacade.getMessage_module_moduleId(pos)) {
		//	graphics.setForegroundColor(EVENT_FG_COLOR); //FIXME temporarily red
		//}
		
		// test if self-message (y1==y2) or not
		if (y1==y2) {

			int halfEllipseHeight = isDelivery ? DELIVERY_SELFARROW_HEIGHT : NONDELIVERY_SELFARROW_HEIGHT;
			
			if (x1==x2) {
				// draw vertical line (as zero-width half ellipse) 
				if (vlineBuffer.vlineContainsNewPixel(x1, y1-halfEllipseHeight, y1))
					graphics.drawLine(x1, y1, x1, y1 - halfEllipseHeight);

				if (needArrowHead)
					drawArrowHead(graphics, x1, y1, 0, 1);

				if (showMessageNames)
					drawMessageArrowLabel(graphics, arrowLabel, x1, y1, 2, -15);
			}
			else {
				// draw half ellipse
				Rectangle.SINGLETON.setLocation(x1, y1 - halfEllipseHeight);
				Rectangle.SINGLETON.setSize(x2-x1, halfEllipseHeight * 2);
				graphics.drawArc(Rectangle.SINGLETON, 0, 180);
				
				if (needArrowHead) {
					// intersection of the ellipse and a circle with the arrow length centered at the end point
					// origin is in the center of the ellipse
					// mupad: solve([x^2/a^2+(r^2-(x-a)^2)/b^2=1],x,IgnoreSpecialCases)
					double a = Rectangle.SINGLETON.width / 2;
					double b = Rectangle.SINGLETON.height / 2;
					double a2 = a * a;
					double b2 = b * b;
					double r = ARROWHEAD_LENGTH;
					double r2 = r *r;
					double x = a == b ? (2 * a2 - r2) / 2 / a : a * (-Math.sqrt(a2 * r2 + b2 * b2 - b2 * r2) + a2) / (a2 - b2);
					double y = -Math.sqrt(r2 - (x - a) * (x - a));
					
					// if the solution falls outside of the top right quarter of the ellipse
					if (x < 0)
						drawArrowHead(graphics, x2, y2, 0, 1);
					else {
						// shift solution to the coordinate system of the canvas
						x = (x1 + x2) / 2 + x;
						y = y1 + y;
						drawArrowHead(graphics, x2, y2, x2 - x, y2 - y);
					}
				}

				if (showMessageNames)
					drawMessageArrowLabel(graphics, arrowLabel, (x1 + x2) / 2, y1, 0, -halfEllipseHeight - 15);
			}
		}
		else {
			// draw straight line
			if (x1!=x2 || vlineBuffer.vlineContainsNewPixel(x1, y1, y2))
				graphics.drawLine(x1, y1, x2, y2);
			
			if (needArrowHead)
				drawArrowHead(graphics, x2, y2, x2 - x1, y2 - y1);
			
			if (showMessageNames)
				drawMessageArrowLabel(graphics, arrowLabel, (x1 + x2) / 2, (y1 + y2) / 2, 2, y1 < y2 ? -15 : 0);
		}
	}
	
	private void drawMessageArrowLabel(Graphics graphics, String label, int x, int y, int dx, int dy)
	{
		if (MESSAGE_LABEL_COLOR!=null)
			graphics.setForegroundColor(MESSAGE_LABEL_COLOR);
		graphics.drawText(label, x + dx, y + dy);
	}
	
	/**
	 * Draws an arrowhead.
	 * XXX what are the parameters? document!!!
	 */
	private void drawArrowHead(Graphics graphics, int x, int y, double dx, double dy)
	{
		double n = Math.sqrt(dx * dx + dy * dy);
		double dwx = -dy / n * ARROWHEAD_WIDTH / 2;
		double dwy = dx / n * ARROWHEAD_WIDTH / 2;
		double xt = x - dx * ARROWHEAD_LENGTH / n;
		double yt = y - dy * ARROWHEAD_LENGTH / n;
		int x1 = (int)Math.round(xt - dwx);
		int y1 = (int)Math.round(yt - dwy);
		int x2 = (int)Math.round(xt + dwx);
		int y2 = (int)Math.round(yt + dwy);

		graphics.setBackgroundColor(ARROWHEAD_COLOR!=null ? ARROWHEAD_COLOR : graphics.getForegroundColor());
		graphics.fillPolygon(new int[] {x, y, x1, y1, x2, y2});
	}

	/**
	 * Draws the axis, according to the current pixelsPerTimelineUnit and tickInterval
	 * settings. Does NOT include axis labels which go on the non-cachable layer.
	 */
	private void drawAxis(Graphics graphics, int y) {
		Rectangle rect = graphics.getClip(Rectangle.SINGLETON);

		// draw axis
		graphics.setForegroundColor(AXIS_COLOR);
		graphics.drawLine(rect.x, y, rect.right(), y);

		int h = axisSpacing<AXISLABEL_DISTANCE ? 1 : 2; // make tick lines shorted when axes are dense
		for (BigDecimal tick : ticks)
		{
			int x = simulationTimeToPixel(tick.doubleValue());
			graphics.drawLine(x, y-h, x, y+h);
			graphics.drawText(tick.toPlainString() + "s", x, y+3);
		}
	}

	/**
	 * Calculates and stores ticks as simulation times based on tickScale. Tries to round tick values
	 * to have as short numbers as possible within within a range.
	 */
	private void calculateTicks(Graphics graphics) {
		ticks.clear();
		Rectangle rect = graphics.getClip(Rectangle.SINGLETON);

		double tleft = pixelToSimulationTime(rect.x);
		double tright = pixelToSimulationTime(rect.right());
		//System.out.println("simtime interval: "+tleft+" - "+tright);

		// calculate ticks and labels
		BigDecimal tickStart = new BigDecimal(tleft).setScale(-tickScale, RoundingMode.FLOOR);
		BigDecimal tickEnd = new BigDecimal(tright).setScale(-tickScale, RoundingMode.CEILING);
		BigDecimal tickIntvl = new BigDecimal(1).scaleByPowerOfTen(tickScale);
		//System.out.println(tickStart+" - "+tickEnd+ " step "+tickIntvl);

		int halfTickRange = (int)Math.round(tickIntvl.doubleValue() * pixelsPerTimelineUnit / 2);
		for (BigDecimal t = tickStart; t.compareTo(tickEnd) < 0;) {
			int x = simulationTimeToPixel(t.doubleValue());
			BigDecimal tMin = new BigDecimal(pixelToSimulationTime(x - halfTickRange));
			BigDecimal tMax = new BigDecimal(pixelToSimulationTime(x + halfTickRange));
			int tMinPrecision = tMin.stripTrailingZeros().precision();
			int tMaxPrecision = tMax.stripTrailingZeros().precision();
			int tDeltaPrecision = tMax.subtract(tMin).stripTrailingZeros().precision();
			MathContext mc = new MathContext(1 + Math.max(tMinPrecision - tDeltaPrecision, tMaxPrecision - tDeltaPrecision));
			BigDecimal tRounded = t;
			BigDecimal tBestRounded = tRounded;

			do
			{
				tRounded = t.round(mc);

				if (tRounded.compareTo(tMin) > 0 && tRounded.compareTo(tMax) < 0)
					tBestRounded = tRounded;
				else
					break;
				
		
				if (mc.getPrecision() > 0)
					mc = new MathContext(mc.getPrecision() - 1);
				
			}
			while (mc.getPrecision() > 0);

			ticks.add(tBestRounded);
			t = new BigDecimal(timelineCoordinateToSimulationTime(simulationTimeToTimelineCoordinate(t.doubleValue()) + tickIntvl.doubleValue()));
		}
	}

	/**
	 * Draws axis labels if there's enough space between axes.
	 */
	private void paintAxisLabels(Graphics graphics) {
		if (AXISLABEL_DISTANCE < axisSpacing) {
			graphics.setForegroundColor(LABEL_COLOR);
			for (int i=0; i<axisModules.size(); i++) {
				ModuleTreeItem treeItem = axisModules.get(i);
				int y = (int)(getAxisY(i) - getViewportTop());
				String label = treeItem.getModuleFullPath();
				graphics.drawText(label, 5, y - AXISLABEL_DISTANCE);
			}
		}
	}

	/**
	 * Calculates the Y coordinate for the ith axis.
	 */
	private int getAxisY(int i) {
		return axisOffset + axisModulePositions[i] * axisSpacing;
	}

	/**
	 * Calculates timeline coordinates for all events. It might be a non-linear transformation
	 * of simulation time, event number, etc.
	 */
	private void recalculateTimelineCoordinates()
	{
		double previousSimulationTime = 0;
		double previousTimelineCoordinate = 0;
		int size = logFacade.getNumEvents();

		for (int i=0; i<size; i++) {
        	double simulationTime = logFacade.getEvent_i_simulationTime(i);

        	switch (timelineMode)
        	{
	        	case LINEAR:
	        		logFacade.setEvent_i_timelineCoordinate(i, simulationTime);
	        		break;
	        	case STEP:
	        		logFacade.setEvent_i_timelineCoordinate(i, i);
	        		break;
	        	case NON_LINEAR:
	        		double timelineCoordinate = previousTimelineCoordinate + Math.atan((simulationTime - previousSimulationTime) / nonLinearFocus) / Math.PI * 2;
	        		logFacade.setEvent_i_timelineCoordinate(i, timelineCoordinate);
	        		previousTimelineCoordinate = timelineCoordinate;
	        		break;
        	}
        	
        	previousSimulationTime = simulationTime;
    	}

		nonLinearFocus = previousSimulationTime / size / 10;
	}

	/**
	 * Translates from simulation time to timeline coordinate.
	 */
	private double simulationTimeToTimelineCoordinate(double simulationTime)
	{		
    	switch (timelineMode)
    	{
        	case LINEAR:
        		return simulationTime;
        	case STEP:
        	case NON_LINEAR:
        		int pos = eventLog.getLastEventPositionBefore(simulationTime);
        		double eventSimulationTime;
        		double eventTimelineCoordinate;
        		
        		// if before the first event
        		if (pos == -1) {
        			eventSimulationTime = 0;
        			eventTimelineCoordinate = 0;
        		}
        		else {
        			eventSimulationTime = logFacade.getEvent_i_simulationTime(pos);
        			eventTimelineCoordinate = logFacade.getEvent_i_timelineCoordinate(pos);
    			}

        		// after the last event simulationTime and timelineCoordinate are proportional
        		if (pos == eventLog.getNumEvents() - 1)
        			return eventTimelineCoordinate + simulationTime - eventSimulationTime;

    			// linear approximation between two enclosing events
        		double simulationTimeDelta = logFacade.getEvent_i_simulationTime(pos + 1) - eventSimulationTime;
        		double timelineCoordinateDelta = logFacade.getEvent_i_timelineCoordinate(pos + 1) - eventTimelineCoordinate;
           		
        		if (simulationTimeDelta == 0) //XXX this can happen in STEP mode when pos==-1, and 1st event is at timeline zero. perhaps getLastEventPositionBeforeByTimelineCoordinate() should check "<=" not "<" ?
        			return eventTimelineCoordinate;
        		Assert.isTrue(simulationTimeDelta > 0);

        		return eventTimelineCoordinate + timelineCoordinateDelta * (simulationTime - eventSimulationTime) / simulationTimeDelta;
        	default:
        		throw new RuntimeException("Unknown timeline mode");
    	}
	}
	
	/**
	 * Translates from timeline coordinate to simulation time.
	 */
	private double timelineCoordinateToSimulationTime(double timelineCoordinate)
	{
    	switch (timelineMode)
    	{
        	case LINEAR:
        		return timelineCoordinate;
        	case STEP:
        	case NON_LINEAR:
        		int pos = eventLog.getLastEventPositionBeforeByTimelineCoordinate(timelineCoordinate);
        		double eventSimulationTime;
        		double eventTimelineCoordinate;
        		
           		// if before the first event
        		if (pos == -1) {
        			eventSimulationTime = 0;
        			eventTimelineCoordinate = 0;
        		}
        		else {
        			eventSimulationTime = logFacade.getEvent_i_simulationTime(pos);
        			eventTimelineCoordinate = logFacade.getEvent_i_timelineCoordinate(pos);
        		}

        		// after the last event simulationTime and timelineCoordinate are proportional
        		if (pos == eventLog.getNumEvents() - 1)
        			return eventSimulationTime + timelineCoordinate - eventTimelineCoordinate;

    			// linear approximation between two enclosing events
        		double simulationTimeDelta = logFacade.getEvent_i_simulationTime(pos + 1) - eventSimulationTime;
        		double timelineCoordinateDelta = logFacade.getEvent_i_timelineCoordinate(pos + 1) - eventTimelineCoordinate;
        		if (timelineCoordinateDelta == 0) //XXX this can happen in STEP mode when pos==-1, and 1st event is at timeline zero. perhaps getLastEventPositionBeforeByTimelineCoordinate() should check "<=" not "<" ?
        			return eventSimulationTime;
        		Assert.isTrue(timelineCoordinateDelta > 0);
        		return eventSimulationTime + simulationTimeDelta * (timelineCoordinate - eventTimelineCoordinate) / timelineCoordinateDelta;
        	default:
        		throw new RuntimeException("Unknown timeline mode");
    	}
	}
	
	/**
	 * Translates from pixel x coordinate to seconds.
	 */
	private double pixelToSimulationTime(int x) {
		return timelineCoordinateToSimulationTime(pixelToTimelineCoordinate(x));
	}
	
	/**
	 * Translates from seconds to pixel x coordinate.
	 */
	private int simulationTimeToPixel(double t) {
		return (int)((long)Math.round(simulationTimeToTimelineCoordinate(t) * pixelsPerTimelineUnit) - getViewportLeft());
	}
	
	/**
	 * Translates from pixel x coordinate to timeline coordinate, using on pixelsPerTimelineUnit.
	 */
	private double pixelToTimelineCoordinate(int x) {
		return (x+getViewportLeft()) / pixelsPerTimelineUnit;
	}

	/**
	 * Translates timeline coordinate to pixel x coordinate, using on pixelsPerTimelineUnit.
	 * Extreme values get clipped to a reasonable interval (-XMAX, XMAX).
	 */
	private int timelineCoordinateToPixel(double t) {
		long x = Math.round(t * pixelsPerTimelineUnit) - getViewportLeft();
    	return (x < -XMAX) ? -XMAX : (x > XMAX) ? XMAX : (int)x;
	}

	/**
	 * Sets up default mouse handling.
	 */
	private void setUpMouseHandling() {
		// dragging ("hand" cursor) and tooltip
		addMouseListener(new MouseListener() {
			public void mouseDoubleClick(MouseEvent e) {}
			public void mouseDown(MouseEvent e) {
				dragStartX = e.x;
				dragStartY = e.y;
				removeTooltip();
			}
			public void mouseUp(MouseEvent e) {
				setCursor(null); // restore cursor at end of drag
				
			}
    	});
		addMouseTrackListener(new MouseTrackListener() {
			public void mouseEnter(MouseEvent e) {}
			public void mouseExit(MouseEvent e) {}
			public void mouseHover(MouseEvent e) {
				if ((e.stateMask & SWT.BUTTON_MASK) == 0)
					displayTooltip(e.x, e.y);
			}
		});
		addMouseMoveListener(new MouseMoveListener() {
			public void mouseMove(MouseEvent e) {
				removeTooltip();
				setCursor(null); // restore cursor at end of drag (must do it here too, because we 
								 // don't get the "released" event if user releases mouse outside the canvas)
				if ((e.stateMask & SWT.BUTTON_MASK) != 0)
					myMouseDragged(e);
			}

			private void myMouseDragged(MouseEvent e) {
				// display drag cursor
				setCursor(DRAGCURSOR);
				
				// scroll by the amount moved since last drag call
				int dx = e.x - dragStartX;
				int dy = e.y - dragStartY;
				scrollHorizontalTo(getViewportLeft() - dx);
				scrollVerticalTo(getViewportTop() - dy);
				dragStartX = e.x;
				dragStartY = e.y;
			}
		});
		// selection handling
		addMouseListener(new MouseListener() {
			public void mouseDoubleClick(MouseEvent me) {
				ArrayList<EventEntry> tmp = new ArrayList<EventEntry>();
				collectStuffUnderMouse(me.x, me.y, tmp, null);
				if (eventListEquals(selectionEvents, tmp)) {
					fireSelection(true);
				}
				else {
					selectionEvents = tmp;
					fireSelection(true);
					fireSelectionChanged();
					redraw();
				}
			}
			public void mouseDown(MouseEvent me) {
				//XXX improve mouse handling: starting dragging should not deselect events!
				if (me.button==1) {
					ArrayList<EventEntry> tmp = new ArrayList<EventEntry>();
					if ((me.stateMask & SWT.CTRL)!=0) // CTRL key extends selection
						for (EventEntry e : selectionEvents) 
							tmp.add(e);
					collectStuffUnderMouse(me.x, me.y, tmp, null);
					if (eventListEquals(selectionEvents, tmp)) {
						fireSelection(false);
					}
					else {
						selectionEvents = tmp;
						fireSelection(false);
						fireSelectionChanged();
						redraw();
					}
					
				}
			}
			public void mouseUp(MouseEvent me) {}
		});
	}

	/**
	 * Utility function, used in selection change handling
	 */
	private static boolean eventListEquals(List<EventEntry> a, List<EventEntry> b) {
		if (a==null || b==null)
			return a==b;
		if (a.size() != b.size())
			return false;
		for (int i=0; i<a.size(); i++)
			if (a.get(i).getEventNumber() != b.get(i).getEventNumber()) // cannot use a.get(i)==b.get(i) because SWIG return new instances every time
				return false;
		return true;
	}
	
	protected void displayTooltip(int x, int y) {
		String tooltipText = getTooltipText(x,y);
		if (tooltipText!=null) {
			tooltipWidget = new DefaultInformationControl(getShell());
			tooltipWidget.setInformation(tooltipText);
			tooltipWidget.setLocation(toDisplay(x,y+20));
			Point size = tooltipWidget.computeSizeHint();
			tooltipWidget.setSize(size.x, size.y);
			tooltipWidget.setVisible(true);
		}
	}

	protected void removeTooltip() {
		if (tooltipWidget!=null) {
			tooltipWidget.setVisible(false);
			tooltipWidget.dispose();
			tooltipWidget = null;
		}
	}

	/**
	 * Calls collectStuffUnderMouse(), and assembles a possibly multi-line
	 * tooltip text from it. Returns null if there's no text to display.
	 */
	protected String getTooltipText(int x, int y) {
		ArrayList<EventEntry> events = new ArrayList<EventEntry>();
		ArrayList<MessageEntry> msgs = new ArrayList<MessageEntry>();
		collectStuffUnderMouse(x, y, events, msgs);

		// 1) if there are events under them mouse, show them in the tooltip
		if (events.size()>0) {
			String res = "";
			int count = 0;
			for (EventEntry event : events) {
				if (count++ > MAX_TOOLTIP_LINES) {
					res += "...and "+(events.size()-count)+" more";
					break;
				}
				res += "event #"+event.getEventNumber()+" at t="+event.getSimulationTime()
		    		+"  at ("+event.getCause().getModule().getModuleClassName()+")"
		    		+event.getCause().getModule().getModuleFullPath()
		    		+" (id="+event.getCause().getModule().getModuleId()+")"
		    		+"  message ("+event.getCause().getMessageClassName()+")"
		    		+event.getCause().getMessageName()+"\n";
			}
			res = res.trim();
			return res;
		}
			
		// 2) no events: show message arrows info
		if (msgs.size()>=1) {
			String res = "";
			int count = 0;
			for (MessageEntry msg : msgs) {
				// truncate tooltip
				if (count++ > MAX_TOOLTIP_LINES) {
					res += "...and "+(msgs.size()-count)+" more";
					break;
				}
				// add message
				res += "message ("+msg.getMessageClassName()+") "+msg.getMessageName()
					+"  ("+ (msg.getIsDelivery() ? "sending" : "usage")  //TODO also: "selfmsg"
					+", #"+msg.getSource().getEventNumber()+" -> #"+msg.getTarget().getEventNumber()+")\n"; 
			}
			res = res.trim();
			return res;
		}

		// 3) no events or message arrows: show axis info
		ModuleTreeItem axisModule = findAxisAt(y);
		if (axisModule!=null) {
			String res = "axis "+axisModule.getModuleFullPath()+"\n";
			double t = pixelToSimulationTime(x);
			res += String.format("t = %gs", t);
			EventEntry event = eventLog.getLastEventBefore(t);
			if (event!=null)
				res += ", just after event #"+event.getEventNumber(); 
			return res;
		}

		// absolutely nothing to say
		return null;
	}

	/**
	 * Returns the axis at the given Y coordinate (with MOUSE_TOLERANCE), or null. 
	 */
	public ModuleTreeItem findAxisAt(int y) {
		// determine which axis (1st, 2nd, etc) is nearest, and if it's "near enough"
		long nearestAxisPos = (y + getViewportTop() - axisOffset + axisSpacing/2) / axisSpacing;
		long nearestAxisY = axisOffset + nearestAxisPos * axisSpacing - getViewportTop();
		if (Math.abs(y - nearestAxisY) > MOUSE_TOLERANCE)
			return null; // nothing here
			
		// find which ModuleTreeItem this axis corresponds to
		int axisModuleIndex = -1;
		for (int i=0; i<axisModulePositions.length; i++)
			if (axisModulePositions[i]==nearestAxisPos)
				axisModuleIndex = i;
		Assert.isTrue(axisModuleIndex>=0);
		return axisModules.get(axisModuleIndex);
	}
	
	/**
	 * Determines if there are any events (EventEntry) or messages (MessageEntry)
	 * at the given mouse coordinates, and returns them in the Lists passed. 
	 * Coordinates are canvas coordinates (more precisely, viewport coordinates).
	 * You can call this method from mouse click, double-click or hover event 
	 * handlers. 
	 * 
	 * If you're interested only in messages or only in events, pass null in the
	 * events or msgs argument. This method does NOT clear the lists before filling them.
	 */
	public void collectStuffUnderMouse(int mouseX, int mouseY, List<EventEntry> events, List<MessageEntry> msgs) {
		if (eventLog!=null) {
			long startMillis = System.currentTimeMillis();
		
			// determine start/end event numbers
			int[] eventIndexRange = getFirstLastEventIndicesInRange(0, getClientArea().width);
			int startEventIndex = eventIndexRange[0];
			int endEventIndex = eventIndexRange[1];
			int startEventNumber = logFacade.getEvent_i_eventNumber(startEventIndex);
			int endEventNumber = logFacade.getEvent_i_eventNumber(endEventIndex);

			// check events
            if (events != null) {
            	for (int i=startEventIndex; i<=endEventIndex; i++)
   				if (eventSymbolContainsPoint(mouseX, mouseY, (int)(logFacade.getEvent_i_cachedX(i)-getViewportLeft()), (int)(logFacade.getEvent_i_cachedY(i)-getViewportTop()), MOUSE_TOLERANCE))
   					events.add(eventLog.getEvent(i));
            }

            // check message arrows
            if (msgs != null) {
    			// collect msgs
            	IntVector msgsIndices = eventLog.getMessagesIntersecting(startEventNumber, endEventNumber, moduleIds, showNonDeliveryMessages); 
        		//System.out.printf("interval: #%d, #%d, %d msgs to check\n",startEventNumber, endEventNumber, msgsIndices.size());
            	for (int i=0; i<msgsIndices.size(); i++) {
            		int pos = msgsIndices.get(i);
            		if (messageArrowContainsPoint(pos, mouseX, mouseY, MOUSE_TOLERANCE))
            			msgs.add(eventLog.getMessage(pos));
            	}
            }
            long millis = System.currentTimeMillis()-startMillis;
            //System.out.println("collectStuffUnderMouse(): "+millis+"ms - "+(events==null ? "n/a" : events.size())+" events, "+(msgs==null ? "n/a" : msgs.size())+" msgs");
		}
	}

	/**
	 * Utility function, to detect whether use clicked (hovered) an event in the the chart
	 */
	private boolean eventSymbolContainsPoint(int x, int y, int px, int py, int tolerance) {
		return Math.abs(x-px) <= 2+tolerance && Math.abs(y-py) <= 5+tolerance;
	}

	private boolean messageArrowContainsPoint(int pos, int px, int py, int tolerance) {
        int x1 = (int)(logFacade.getMessage_source_cachedX(pos) - getViewportLeft());
        int y1 = (int)(logFacade.getMessage_source_cachedY(pos) - getViewportTop());
        int x2 = (int)(logFacade.getMessage_target_cachedX(pos) - getViewportLeft());
        int y2 = (int)(logFacade.getMessage_target_cachedY(pos) - getViewportTop());
		//System.out.printf("checking %d %d %d %d\n", x1, x2, y1, y2);
		if (y1==y2) {
			int height = logFacade.getMessage_isDelivery(pos) ? DELIVERY_SELFARROW_HEIGHT : NONDELIVERY_SELFARROW_HEIGHT;
			return halfEllipseContainsPoint(x1, x2, y1, height, px, py, tolerance);
		}
		else {
			return lineContainsPoint(x1, y1, x2, y2, px, py, tolerance);
		}
	}
	
	private boolean halfEllipseContainsPoint(int x1, int x2, int y, int height, int px, int py, int tolerance) {
		tolerance++;

		Rectangle.SINGLETON.setSize(0, 0);
		Rectangle.SINGLETON.setLocation(x1, y);
		Rectangle.SINGLETON.union(x2, y-height);
		Rectangle.SINGLETON.expand(tolerance, tolerance);
		if (!Rectangle.SINGLETON.contains(px, py))
			return false;

		int x = (x1+x2) / 2;
		int rx = Math.abs(x1-x2) / 2;
		int ry = height;

        if (rx == 0)
        	return true;
		
		int dxnorm = (x - px) * ry / rx;
		int dy = y - py;
		int distSquare = dxnorm*dxnorm + dy*dy;
		return distSquare < (ry+tolerance)*(ry+tolerance) && distSquare > (ry-tolerance)*(ry-tolerance); 
	}

	/**
	 * Utility function, copied from org.eclipse.draw2d.Polyline.
	 */
	private boolean lineContainsPoint(int x1, int y1, int x2, int y2, int px, int py, int tolerance) {
		Rectangle.SINGLETON.setSize(0, 0);
		Rectangle.SINGLETON.setLocation(x1, y1);
		Rectangle.SINGLETON.union(x2, y2);
		Rectangle.SINGLETON.expand(tolerance, tolerance);
		if (!Rectangle.SINGLETON.contains(px, py))
			return false;

		int v1x, v1y, v2x, v2y;
		int numerator, denominator;
		int result = 0;

		// calculates the length squared of the cross product of two vectors, v1 & v2.
		if (x1 != x2 && y1 != y2) {
			v1x = x2 - x1;
			v1y = y2 - y1;
			v2x = px - x1;
			v2y = py - y1;

			numerator = v2x * v1y - v1x * v2y;

			denominator = v1x * v1x + v1y * v1y;

			result = (int)((long)numerator * numerator / denominator);
		}

		// if it is the same point, and it passes the bounding box test,
		// the result is always true.
		return result <= tolerance * tolerance;
	}

	/**
     * Add a selection change listener.
     */
	public void addSelectionChangedListener(ISelectionChangedListener listener) {
        selectionChangedListeners.add(listener);
	}

	/**
     * Remove a selection change listener.
     */
	public void removeSelectionChangedListener(ISelectionChangedListener listener) {
		selectionChangedListeners.remove(listener);
	}
	
	/**
     * Notifies any selection changed listeners that the viewer's selection has changed.
     * Only listeners registered at the time this method is called are notified.
     */
    protected void fireSelectionChanged() {
        Object[] listeners = selectionChangedListeners.getListeners();
        final SelectionChangedEvent event = new SelectionChangedEvent(this, getSelection());        
        for (int i = 0; i < listeners.length; ++i) {
            final ISelectionChangedListener l = (ISelectionChangedListener) listeners[i];
            SafeRunnable.run(new SafeRunnable() {
                public void run() {
                    l.selectionChanged(event);
                }
            });
        }
    }

	/**
	 * Returns the currently "selected" events as an instance of IEventLogSelection.
	 * Selection is shown as red circles in the chart.
	 */
	public ISelection getSelection() {
		return new EventLogSelection(eventLog, selectionEvents);
	}

	/**
	 * Sets the currently "selected" events. The selection must be an
	 * instance of IEventLogSelection and refer to the current eventLog, 
	 * otherwise the call will be ignored. Selection is displayed as red
	 * circles in the graph.
	 */
	public void setSelection(ISelection newSelection) {
		System.out.println("SeqChartFigure got selection: "+newSelection);
		
		if (!(newSelection instanceof IEventLogSelection))
			return; // wrong selection type
		IEventLogSelection newEventLogSelection = (IEventLogSelection)newSelection;
		if (newEventLogSelection.getEventLog() != eventLog)
			return;  // wrong -- refers to another eventLog

		// if new selection differs from existing one, take over its contents
		if (!eventListEquals(newEventLogSelection.getEvents(), selectionEvents)) {
			selectionEvents.clear();
			for (EventEntry e : newEventLogSelection.getEvents()) 
				selectionEvents.add(e);

			// go to the time of the first event selected
			if (selectionEvents.size()>0)
				gotoSimulationTime(selectionEvents.get(0).getSimulationTime());

			redraw();
		}
	}
}
