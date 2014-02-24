//==========================================================================
//  OBJINSP.CC - part of
//
//                     OMNeT++/OMNEST
//            Discrete System Simulation in C++
//
//  Implementation of
//    inspectors
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

#include "omnetpp.h"

#include "tkenv.h"
#include "tklib.h"
#include "inspfactory.h"
#include "objinsp.h"

NAMESPACE_BEGIN


void _dummy_for_objinsp() {}

GenericObjectInspector::GenericObjectInspector() : Inspector()
{
    hascontentspage = false;
    focuscontentspage = false;
}

GenericObjectInspector::~GenericObjectInspector()
{
}

void GenericObjectInspector::setObject(cObject *obj)
{
    Inspector::setObject(obj);

    bool showcontentspage =
            dynamic_cast<cArray *>(object) || dynamic_cast<cQueue *>(object) ||
            dynamic_cast<cMessageHeap *>(object) || dynamic_cast<cDefaultList *>(object) ||
            dynamic_cast<cSimulation *>(object) || dynamic_cast<cRegistrationList *>(object);
    bool focuscontentspage =
            dynamic_cast<cArray *>(object) || dynamic_cast<cQueue *>(object) ||
            dynamic_cast<cMessageHeap *>(object) || dynamic_cast<cDefaultList *>(object) ||
            dynamic_cast<cRegistrationList *>(object);
    //FIXME this probably doesn't work now
    setContentsPage(showcontentspage, focuscontentspage);
}

void GenericObjectInspector::createWindow(const char *window, const char *geometry)
{
   Inspector::createWindow(window, geometry);

   // create inspector window by calling the specified proc with
   // the object's pointer. Window name will be like ".ptr80003a9d-1"
   Tcl_Interp *interp = getTkenv()->getInterp();
   CHK(Tcl_VarEval(interp, "createGenericObjectInspector ", windowName, " \"", geometry, "\"", " ",
                   (hascontentspage ? "1" : "0"), " ",  (focuscontentspage ? "1" : "0"), " ", NULL));
}

void GenericObjectInspector::update()
{
   Inspector::update();

   // refresh "fields" page
   Tcl_Interp *interp = getTkenv()->getInterp();
   CHK(Tcl_VarEval(interp, "fields2Page:refresh ", windowName, NULL));

   // refresh "contents" page
   if (hascontentspage)
   {
       deleteInspectorListbox(".nb.contents");
       fillInspectorListbox(".nb.contents", object, false);
   }
}

void GenericObjectInspector::writeBack()
{
   Inspector::writeBack();
}

int GenericObjectInspector::inspectorCommand(Tcl_Interp *interp, int argc, const char **argv)
{
   return TCL_ERROR;
}

class TGenericObjectInspectorFactory : public InspectorFactory
{
  public:
    TGenericObjectInspectorFactory(const char *name) : InspectorFactory(name) {}

    bool supportsObject(cObject *obj) {return true;}
    int getInspectorType() {return INSP_OBJECT;}
    double getQualityAsDefault(cObject *object) {return 1.0;}

    Inspector *createInspector() {
        return prepare(new GenericObjectInspector());
    }
};

Register_InspectorFactory(TGenericObjectInspectorFactory);

//----

class TWatchInspectorFactory : public InspectorFactory
{
  public:
    TWatchInspectorFactory(const char *name) : InspectorFactory(name) {}

    bool supportsObject(cObject *obj) {
        // Return true if it's a watch for a simple type (int, double, string etc).
        // For structures, we prefer the normal TGenericObjectInspector.
        // Currently we're prepared for cStdVectorWatcherBase.
        return dynamic_cast<cWatchBase *>(obj) && !dynamic_cast<cStdVectorWatcherBase *>(obj);
    }
    int getInspectorType() {return INSP_OBJECT;}
    double getQualityAsDefault(cObject *object) {return 2.0;}
    Inspector *createInspector() {
        return prepare(new WatchInspector());
    }
};

Register_InspectorFactory(TWatchInspectorFactory);


WatchInspector::WatchInspector() : Inspector()
{
}

void WatchInspector::createWindow(const char *window, const char *geometry)
{
   Inspector::createWindow(window, geometry);

   // create inspector window by calling the specified proc with
   // the object's pointer. Window name will be like ".ptr80003a9d-1"
   Tcl_Interp *interp = getTkenv()->getInterp();
   CHK(Tcl_VarEval(interp, "createWatchInspector ", windowName, " \"", geometry, "\"", NULL ));
}

void WatchInspector::update()
{
   Inspector::update();

   cWatchBase *watch = static_cast<cWatchBase *>(object);
   setLabel(".main.name.l", (std::string(watch->getClassName())+" "+watch->getName()).c_str());
   setEntry(".main.name.e", watch->info().c_str());
}

void WatchInspector::writeBack()
{
   Tcl_Interp *interp = getTkenv()->getInterp();
   cWatchBase *watch = static_cast<cWatchBase *>(object);
   const char *s = getEntry(".main.name.e");
   if (watch->supportsAssignment())
       watch->assign(s);
   else
      CHK(Tcl_VarEval(interp,"messagebox {Warning} {This inspector doesn't support changing the value.} warning ok", NULL));
   Inspector::writeBack();    // must be there after all changes
}

NAMESPACE_END

