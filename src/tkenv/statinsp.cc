//==========================================================================
//  STATINSP.CC - part of
//
//                     OMNeT++/OMNEST
//            Discrete System Simulation in C++
//
//==========================================================================

/*--------------------------------------------------------------*
  Copyright (C) 1992-2008 Andras Varga
  Copyright (C) 2006-2008 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  `license' for details on this and other legal matters.
*--------------------------------------------------------------*/

#include <string.h>
#include <math.h>

#include "cmodule.h"
#include "cmessage.h"
#include "cpar.h"
#include "carray.h"
#include "coutvector.h"
#include "cstatistic.h"
#include "cdensityestbase.h"

#include "tkenv.h"
#include "tklib.h"
#include "inspfactory.h"
#include "statinsp.h"

NAMESPACE_BEGIN

void _dummy_for_statinsp() {}


class THistogramWindowFactory : public InspectorFactory
{
  public:
    THistogramWindowFactory(const char *name) : InspectorFactory(name) {}

    bool supportsObject(cObject *obj) {return dynamic_cast<cDensityEstBase *>(obj)!=NULL;}
    int getInspectorType() {return INSP_GRAPHICAL;}
    double getQualityAsDefault(cObject *object) {return 3.0;}

    Inspector *createInspector() {
        return prepare(new HistogramInspector());
    }
};

Register_InspectorFactory(THistogramWindowFactory);


HistogramInspector::HistogramInspector() : Inspector()
{
}

void HistogramInspector::createWindow(const char *window, const char *geometry)
{
   Inspector::createWindow(window, geometry);

   strcpy(canvas,windowName);
   strcat(canvas,".main.canvas");

   // create inspector window by calling the specified proc with
   // the object's pointer. Window name will be like ".ptr80003a9d-1"
   Tcl_Interp *interp = getTkenv()->getInterp();
   CHK(Tcl_VarEval(interp, "createHistogramWindow ", windowName, " \"", geometry, "\"", NULL ));
}

void HistogramInspector::update()
{
   Inspector::update();

   Tcl_Interp *interp = getTkenv()->getInterp();
   cDensityEstBase *distr = static_cast<cDensityEstBase *>(object);

   char buf[80];
   generalInfo( buf );
   CHK(Tcl_VarEval(interp, windowName,".bot.info config -text {",buf,"}",NULL));

   // can we draw anything at all?
   if (!distr->isTransformed() || distr->getNumCells()==0) return;

   long num_vals = distr->getCount();
   int basepts = distr->getNumCells()+1;
   int cell;
   double cell_lower, cell_upper;

   double xmin = distr->getBasepoint(0);
   double xrange = distr->getBasepoint(basepts-1) - xmin;

   // determine maximum height (will be used for y scaling)
   double ymax = -1.0; // a good start because all y values are >=0
   cell_upper = distr->getBasepoint(0);
   for (cell=0; cell<basepts-1; cell++)
   {
       // get cell
       cell_lower = cell_upper;
       cell_upper = distr->getBasepoint(cell+1);
       // calculate height
       double y = distr->getCellValue(cell) / (double)(num_vals) / (cell_upper-cell_lower);
       if (y>ymax) ymax=y;
   }

   // get canvas size
   CHK(Tcl_VarEval(interp, "winfo width ",canvas, NULL));
   int canvaswidth = atoi( Tcl_GetStringResult(interp) );
   CHK(Tcl_VarEval(interp, "winfo height ", canvas, NULL));
   int canvasheight = atoi( Tcl_GetStringResult(interp) );

   // temporarily define X() and Y() coordinate translation macros
#define X(x)   (int)(10+((x)-xmin)*((long)canvaswidth-20)/xrange)
#define Y(y)   (int)(canvasheight-10-(y)*((long)canvasheight-20)/ymax)

   // delete previous drawing
   CHK(Tcl_VarEval(interp, canvas," delete all", NULL));

   // draw the histogram
   cell_upper = distr->getBasepoint(0);
   for (cell=0; cell<basepts-1; cell++)
   {
       char tag[16];
       sprintf(tag,"cell%d",cell);

       // get cell
       cell_lower = cell_upper;
       cell_upper = distr->getBasepoint(cell+1);
       // calculate height
       double y = distr->getCellValue(cell) / (double)(num_vals) / (cell_upper-cell_lower);
       // prepare rectangle coordinates
       char coords[64];
       sprintf(coords,"%d %d %d %d", X(cell_lower), Y(0), X(cell_upper), Y(y));
       // draw rectangle
       CHK(Tcl_VarEval(interp, canvas,
                               " create rect ", coords," -tag ",tag,
                               " -fill black -outline black", NULL));
   }
#undef X
#undef Y
}

void HistogramInspector::generalInfo( char *buf )
{
   cDensityEstBase *d = static_cast<cDensityEstBase *>(object);
   if (!d->isTransformed())
       sprintf( buf, "(collecting initial values, N=%ld)", d->getCount());
   else
       sprintf( buf, "Histogram: (%g...%g)  N=%ld  #cells=%d",
                 d->getBasepoint(0), d->getBasepoint(d->getNumCells()),
                 d->getCount(),
                 d->getNumCells()
              );
}

void HistogramInspector::getCellInfo( char *buf, int cell )
{
   cDensityEstBase *d = static_cast<cDensityEstBase *>(object);
   double count = d->getCellValue(cell);
   double cell_lower = d->getBasepoint(cell);
   double cell_upper = d->getBasepoint(cell+1);
   sprintf( buf, "Cell #%d:  (%g...%g)  n=%g  PDF=%g",
                 cell,
                 cell_lower, cell_upper,
                 count,
                 count / (double)(d->getCount()) / (cell_upper-cell_lower)
          );
}

int HistogramInspector::inspectorCommand(Tcl_Interp *interp, int argc, const char **argv)
{
   if (argc<1) {Tcl_SetResult(interp, TCLCONST("wrong argcount"), TCL_STATIC); return TCL_ERROR;}

   if (strcmp(argv[0],"cell")==0)   // 'opp_inspectorcommand <inspector> cell ...'
   {
      if (argc>2) {Tcl_SetResult(interp, TCLCONST("wrong argcount"), TCL_STATIC); return TCL_ERROR;}

      char buf[128];
      if (argc==1)
         generalInfo(buf);
      else
         getCellInfo(buf, atoi(argv[1]) );
      Tcl_SetResult(interp,buf,TCL_VOLATILE);
      return TCL_OK;
   }
   return TCL_ERROR;
}

//=======================================================================
class TOutVectorWindowFactory : public InspectorFactory
{
  public:
    TOutVectorWindowFactory(const char *name) : InspectorFactory(name) {}

    bool supportsObject(cObject *obj) {return dynamic_cast<cOutVector *>(obj)!=NULL;}
    int getInspectorType() {return INSP_GRAPHICAL;}
    double getQualityAsDefault(cObject *object) {return 3.0;}

    Inspector *createInspector() {
        return prepare(new OutputVectorInspector());
    }
};

Register_InspectorFactory(TOutVectorWindowFactory);


CircBuffer::CircBuffer(int size)
{
   siz = size;
   buf = new CBEntry[siz];
   n = 0;
   head = 0;
}

CircBuffer::~CircBuffer()
{
   delete [] buf;
}

void CircBuffer::add(simtime_t t, double value1, double value2)
{
   head = (head+1)%siz;
   CBEntry& p = buf[head];
   p.t = t;
   p.value1 = value1;
   p.value2 = value2;
   if (n<siz) n++;
}

static void record_in_insp(void *data, simtime_t t, double val1, double val2)
{
   OutputVectorInspector *insp = (OutputVectorInspector *) data;
   insp->circbuf.add(t,val1,val2);
}

OutputVectorInspector::OutputVectorInspector() : Inspector(), circbuf(100)
{
   autoscale = true;
   drawingMode = DRAW_LINES;
   miny = 0; maxy = 10;
   timeScale = 1;   // x scaling
   hairlineTime = 0;
}

OutputVectorInspector::~OutputVectorInspector()
{
   // cancel installed callback in inspected outvector
   cOutVector *ov = static_cast<cOutVector *>(object);
   ov->setCallback(NULL, NULL);
}

void OutputVectorInspector::setObject(cObject *obj)
{
    if (object) {
        cOutVector *ov = static_cast<cOutVector *>(object);
        ov->setCallback(NULL, NULL);
    }

    Inspector::setObject(obj);

    // make inspected output vector to call us back when it gets data to write out
    cOutVector *ov = static_cast<cOutVector *>(object);
    ov->setCallback(record_in_insp, (void *)this);
}

void OutputVectorInspector::createWindow(const char *window, const char *geometry)
{
   Inspector::createWindow(window, geometry);

   strcpy(canvas,windowName);
   strcat(canvas,".main.canvas");

   // create inspector window by calling the specified proc with
   // the object's pointer. Window name will be like ".ptr80003a9d-1"
   Tcl_Interp *interp = getTkenv()->getInterp();
   CHK(Tcl_VarEval(interp, "createOutvectorWindow ", windowName, " \"", geometry, "\"", NULL ));
}

void OutputVectorInspector::update()
{
   Inspector::update();

   Tcl_Interp *interp = getTkenv()->getInterp();

   char buf[80];
   generalInfo( buf );
   setLabel(".bot.info",buf);

   if (circbuf.items()==0) return;

   // get canvas size
   CHK(Tcl_VarEval(interp, "winfo width ",canvas, NULL));
   int canvasWidth = atoi( Tcl_GetStringResult(interp) );
   if (!canvasWidth)  canvasWidth=1;
   CHK(Tcl_VarEval(interp, "winfo height ", canvas, NULL));
   int canvasHeight = atoi( Tcl_GetStringResult(interp) );
   if (!canvasHeight) canvasHeight=1;

   simtime_t tbase = simulation.getSimTime();
   // simtime_t tbase = circbuf.entry[circbuf.headPos()].t;

   if (autoscale)
   {
       // adjust time_factor if it's too far from the optimal value
       simtime_t firstt = circbuf.entry(circbuf.tailPos()).t;
       double dt = SIMTIME_DBL(tbase - firstt);
       if (dt>0)
       {
          double opt_tf = dt/canvasWidth;

          // some rounding: keep 2 significant digits
          double order = pow(10.0, (int)floor(log10(opt_tf)));
          opt_tf = floor(opt_tf/order+.5)*order;

          // adjust only if it differs >=20% from its optimal value
          if (fabs(timeScale-opt_tf) > opt_tf*0.20)
              timeScale = opt_tf;
       }

       // determine miny and maxy
       int pos = circbuf.headPos();
       miny = maxy = circbuf.entry(circbuf.headPos()).value1;
       for(int i=0;i<circbuf.items();i++)
       {
           CircBuffer::CBEntry& p = circbuf.entry(pos);
           if (p.value1<miny) miny = p.value1;
           if (p.value1>maxy) maxy = p.value1;
           pos=(pos-1+circbuf.size())%circbuf.size();
       }
       if (miny==maxy)
       {
           if (miny!=0) {miny-=fabs(miny/3); maxy+=fabs(maxy/3);}
           else  {miny=-0.5; maxy=0.5;}
       }
   }
   double rangey=maxy-miny;

   simtime_t tf = timeScale;

   // temporarily define X() and Y() coordinate translation macros
#define X(t)   (int)(canvasWidth-10-(tbase-(t))/tf)
#define Y(y)   (int)(canvasHeight-20-((y)-miny)*((long)canvasHeight-30)/rangey)

   //printf("cw=%d  ch=%d  tbase=%f trange=%f  miny=%f  maxy=%f rangey=%f\n",
   //        canvaswidth, canvasheight,tbase,trange, miny, maxy, rangey);

   // delete previous drawing
   CHK(Tcl_VarEval(interp, canvas," delete all", NULL));

   // now scan through the whole buffer in time-increasing order
   // and draw in the meanwhile

   int y_zero = Y(0);   // so that we don't have to calculate it each time
   int next_x=-1;
   int next_y2=0; // values won't be used (they're there just to prevent warning)
   int next_y1=next_y2; // next_y2 is just used to prevent unused variable warning.
   int x, y1;
   int pos;
   int nextPos = (circbuf.headPos()-circbuf.items()+circbuf.size())%circbuf.size();
   for (int i=0;i<=circbuf.items();i++)
   {
       x  = next_x;
       y1 = next_y1;
       pos = nextPos;

       if (i==circbuf.items())
       {
           next_x = X(simulation.getSimTime());
       }
       else
       {
           nextPos=(nextPos+1)%circbuf.size();
           CircBuffer::CBEntry& p = circbuf.entry(nextPos);
           next_x =  X(p.t);
           next_y1 = Y(p.value1);
           next_y2 = Y(p.value2);
       }

       if (x>=0)
       {
           char tag[16];
           sprintf(tag,"value%d",pos);

           const int d = 2;
           char coords[64];
           switch (drawingMode)
           {
             case DRAW_DOTS:
                // draw rectangle 1
                sprintf(coords,"%d %d %d %d", x-d, y1-d, x+d, y1+d);
                CHK(Tcl_VarEval(interp, canvas," create rect ",coords,
                                    " -tag ",tag," -outline red -fill red", NULL));
                break;
             case DRAW_PINS:
                // draw rectangle 1
                sprintf(coords,"%d %d %d %d", x, y_zero, x, y1);
                CHK(Tcl_VarEval(interp, canvas," create line ",coords,
                                    " -tag ",tag," -fill red", NULL));
                break;
             case DRAW_LINES:
                // draw rectangle 1
                sprintf(coords,"%d %d %d %d", x, y1, next_x, next_y1);
                CHK(Tcl_VarEval(interp, canvas," create line ",coords,
                                    " -tag ",tag," -fill red", NULL));
                break;
             case DRAW_SAMPLEHOLD:
                // draw rectangle 1
                sprintf(coords,"%d %d %d %d", x, y1, next_x, y1);
                CHK(Tcl_VarEval(interp, canvas," create line ",coords,
                                    " -tag ",tag," -fill red", NULL));
                break;
             case DRAW_BARS:
                // draw rectangle 1
                sprintf(coords,"%d %d %d %d", x, y_zero, next_x, y1);
                CHK(Tcl_VarEval(interp, canvas," create rect ",coords,
                                    " -tag ",tag," -outline red -fill red", NULL));
                break;
           }
       }
   }

   simtime_t tmin = tbase - tf * (canvasWidth-20);

   if (hairlineTime < tmin)
       hairlineTime = tbase;

   double midy = (miny+maxy)/2;

   // add some basic labeling
   char coords[64], value[64];
   sprintf(coords,"%d %d %d %d", X(tmin)-2, Y(miny), X(tbase)+2, Y(miny));
   CHK(Tcl_VarEval(interp, canvas," create line ",coords," -fill black", NULL));

   sprintf(coords,"%d %d %d %d", X(tmin)-2, Y(maxy), X(tbase)+2, Y(maxy));
   CHK(Tcl_VarEval(interp, canvas," create line ",coords," -fill black", NULL));

   sprintf(coords,"%d %d %d %d", X(tbase)-2, Y(midy), X(tbase)+2, Y(midy));
   CHK(Tcl_VarEval(interp, canvas," create line ",coords," -fill black", NULL));

   sprintf(coords,"%d %d %d %d", X(tbase), Y(maxy)-2, X(tbase), Y(miny)+2);
   CHK(Tcl_VarEval(interp, canvas," create line ",coords," -fill black", NULL));

   sprintf(coords,"%d %d %d %d", X(tmin), Y(maxy)-2, X(tmin), Y(miny)+2);
   CHK(Tcl_VarEval(interp, canvas," create line ",coords," -fill black", NULL));

   sprintf(coords,"%d %d %d %d", X(hairlineTime), Y(maxy)-2, X(hairlineTime), Y(miny)+2);
   CHK(Tcl_VarEval(interp, canvas," create line ",coords," -fill black", NULL));

   sprintf(coords,"%d %d", X(tbase)-3, Y(miny));
   sprintf(value,"%.9g", miny);
   CHK(Tcl_VarEval(interp, canvas," create text ",coords," -text ", value, " -anchor se", NULL));

   sprintf(coords,"%d %d", X(tbase)-3, Y(maxy));
   sprintf(value,"%.9g", maxy);
   CHK(Tcl_VarEval(interp, canvas," create text ",coords," -text ", value, " -anchor ne", NULL));

   sprintf(coords,"%d %d", X(tbase)-3, Y(midy));
   sprintf(value,"%.9g", midy);
   CHK(Tcl_VarEval(interp, canvas," create text ",coords," -text ", value, " -anchor e", NULL));

   sprintf(coords,"%d %d", X(tbase)+3, Y(miny));
   sprintf(value,"%s", SIMTIME_STR(tbase));
   CHK(Tcl_VarEval(interp, canvas," create text ",coords," -text ", value, " -anchor ne", NULL));

   sprintf(coords,"%d %d", X(tmin)-3, Y(miny));
   sprintf(value,"%s", SIMTIME_STR(tmin));
   CHK(Tcl_VarEval(interp, canvas," create text ",coords," -text ", value, " -anchor nw", NULL));

   sprintf(coords,"%d %d", X(hairlineTime)-3, Y(miny));
   sprintf(value,"%s", SIMTIME_STR(hairlineTime));
   CHK(Tcl_VarEval(interp, canvas," create text ",coords," -text ", value, " -anchor n", NULL));

#undef X
#undef Y
}

static const char *drawingmodes[] = {
      "dots", "bars", "pins", "sample-hold", "lines", NULL
};

void OutputVectorInspector::generalInfo( char *buf )
{
   if (circbuf.items()==0)
   {
        strcpy(buf,"(no write since opening window)");
        return;
   }

   CircBuffer::CBEntry& p = circbuf.entry(circbuf.headPos());
   sprintf(buf, "Last value: t=%s  value=%g", SIMTIME_STR(p.t), p.value1);
}

void OutputVectorInspector::valueInfo( char *buf, int valueindex )
{
   CircBuffer::CBEntry& p = circbuf.entry(valueindex);
   sprintf(buf, "t=%s  value=%g", SIMTIME_STR(p.t), p.value1);

   hairlineTime = SIMTIME_DBL(p.t);
}

void OutputVectorInspector::getConfig( char *buf )
{
   sprintf(buf,"%d %g %g %g %s", autoscale, timeScale, miny, maxy, drawingmodes[drawingMode] );
}

void OutputVectorInspector::setConfig( bool autosc, double timefac, double min_y, double max_y, const char *mode)
{
   // store new parameters
   autoscale = autosc;
   timeScale = timefac;
   miny = min_y;
   maxy = max_y;

   // corrections on y range
   if (miny>maxy) maxy=miny;
   if (miny==maxy)
   {
       if (miny!=0) {miny-=fabs(miny/3); maxy+=fabs(maxy/3);}
       else  {miny=-0.5; maxy=0.5;}
   }

   // identify drawing style
   int i;
   for (i=0; i<NUM_DRAWINGMODES; i++)
       if (0==strcmp(mode,drawingmodes[i]))
           break;
   if (i!=NUM_DRAWINGMODES)
       drawingMode = i;
}

int OutputVectorInspector::inspectorCommand(Tcl_Interp *interp, int argc, const char **argv)
{
   if (argc<1) {Tcl_SetResult(interp, TCLCONST("wrong argcount"), TCL_STATIC); return TCL_ERROR;}

   if (strcmp(argv[0],"value")==0)   // 'opp_inspectorcommand <inspector> value ...'
   {
      if (argc>2) {Tcl_SetResult(interp, TCLCONST("wrong argcount"), TCL_STATIC); return TCL_ERROR;}

      char buf[128];
      if (argc==1)
         generalInfo(buf);
      else
         valueInfo(buf, atoi(argv[1]));
      Tcl_SetResult(interp,buf,TCL_VOLATILE);
      return TCL_OK;
   }
   else if (strcmp(argv[0],"config")==0)  // 'opp_inspectorcommand <inspector> config ...'
   {
      if (argc!=6 && argc!=1) {Tcl_SetResult(interp, TCLCONST("wrong argcount"), TCL_STATIC); return TCL_ERROR;}

      // get/set configuration: "timefactor miny maxy drawingmode"
      if (argc==1)
      {
         char buf[128];
         getConfig(buf);
         Tcl_SetResult(interp, buf, TCL_VOLATILE);
      }
      else
      {
         setConfig( atoi(argv[1]), atof(argv[2]), atof(argv[3]), atof(argv[4]), argv[5] );
      }
      return TCL_OK;
   }
   return TCL_ERROR;
}

NAMESPACE_END

