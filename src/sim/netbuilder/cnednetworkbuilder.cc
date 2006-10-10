//==========================================================================
//   CNEDNETWORKBUILDER.CC
//
//                     OMNeT++/OMNEST
//            Discrete System Simulation in C++
//
//==========================================================================

/*--------------------------------------------------------------*
  Copyright (C) 2002-2005 Andras Varga

  This file is distributed WITHOUT ANY WARRANTY. See the file
  `terms' for details on this and other legal matters.
*--------------------------------------------------------------*/


#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <iostream>

#include "cmodule.h"
#include "cgate.h"
#include "cchannel.h"
#include "cbasicchannel.h"
#include "ccomponenttype.h"

#include "nedelements.h"
#include "nederror.h"

#include "nedparser.h"
#include "nedxmlparser.h"
#include "neddtdvalidator.h"
#include "nedbasicvalidator.h"
#include "nedsemanticvalidator.h"
#include "xmlgenerator.h"  // for debugging

#include "cneddeclaration.h"
#include "cnednetworkbuilder.h"
#include "cnedresourcecache.h"
#include "cexpressionbuilder.h"


inline bool strnull(const char *s)
{
    return !s || !s[0];
}

static void dump(NEDElement *node)
{
    generateXML(std::cout, node, false);
    std::cout.flush();
}

void cNEDNetworkBuilder::addParameters(cComponent *component, cNEDDeclaration *decl)
{
    int n = decl->numPars();
    for (int i=0; i<n; i++)
    {
        const cNEDDeclaration::ParamDescription& desc = decl->paramDescription(i);
        cPar *value = desc.value ? desc.value->dup() : cPar::createWithType(desc.type);
        component->addPar(desc.name.c_str(), value);
    }
}

void cNEDNetworkBuilder::addGates(cModule *module, cNEDDeclaration *decl)
{
    int n = decl->numGates();
    for (int i=0; i<n; i++)
    {
        const cNEDDeclaration::GateDescription& desc = decl->gateDescription(i);
        const char *gateName = desc.name.c_str();
        module->addGate(gateName, desc.type, desc.isVector);
        if (desc.isVector && desc.gatesize!=NULL)
            module->setGateSize(gateName, desc.gatesize->longValue(module));
    }
}

cModule *cNEDNetworkBuilder::_submodule(cModule *, const char *submodname, int idx)
{
    SubmodMap::iterator i = submodMap.find(std::string(submodname));
    if (i==submodMap.end())
        return NULL;

    ModulePtrVector& v = i->second;
    if (idx<0)
        return (v.size()!=1 || v[0]->isVector()) ? NULL : v[0];
    else
        return ((unsigned)idx>=v.size()) ? NULL : v[idx];
}

void cNEDNetworkBuilder::buildInside(cModule *modp, cNEDDeclaration *decl)
{
    // set display string
//XXX    setBackgroundDisplayString(modp, modulenode);

    // loop through submods and add them
    submodMap.clear();

    buildRecursively(modp, decl);

    // recursively build the submodules too (top-down)
    for (cSubModIterator submod(*modp); !submod.end(); submod++)
    {
       cModule *m = submod();
       m->buildInside();
    }
}

void cNEDNetworkBuilder::buildRecursively(cModule *modp, cNEDDeclaration *decl)
{
    if (decl->numExtendsNames() > 0)
    {
        const char *superName = decl->extendsName(0);
        cNEDDeclaration *superDecl = cNEDResourceCache::instance()->lookup2(superName);
        ASSERT(superDecl!=NULL);
        buildRecursively(modp, superDecl);
    }

    addSubmodulesAndConnections(modp, decl);
}

void cNEDNetworkBuilder::addSubmodulesAndConnections(cModule *modp, cNEDDeclaration *decl)
{
    printf("buildInside of %s, decl %s\n", modp->fullPath().c_str(), decl->name()); //XXX
    //dump(decl->getTree());

    SubmodulesNode *submods = decl->getSubmodules();
    if (submods)
    {
        for (SubmoduleNode *submod=submods->getFirstSubmoduleChild(); submod; submod=submod->getNextSubmoduleNodeSibling())
        {
            addSubmodule(modp, submod);
        }
    }

    // loop through connections and add them
    ConnectionsNode *conns = decl->getConnections();
    if (conns)
    {
        for (NEDElement *child=conns->getFirstChild(); child; child=child->getNextSibling())
        {
            if (child->getTagCode()==NED_CONNECTION)
                addConnection(modp, (ConnectionNode *)child);
            else if (child->getTagCode()==NED_CONNECTION_GROUP)
                addConnectionGroup(modp, (ConnectionGroupNode *)child);
        }
    }


    // check if there are unconnected gates left
    //FIXME not quite like this, BUT: if allowUnconnected=false, must check gates of submodules ADDED HERE (not all!)
    if (!conns || conns->getAllowUnconnected())
        modp->checkInternalConnections();
}

/*XXX
cChannel *cNEDNetworkBuilder::createChannel(const char *name, ChannelNode *channelnode)
{
    cBasicChannel *chanp = new cBasicChannel(name);
    for (ChannelAttrNode *chattr=channelnode->getFirstChannelAttrChild(); chattr; chattr=chattr->getNextChannelAttrNodeSibling())
    {
        addChannelAttr(chanp, chattr);
    }
    return chanp;
}
*/

/*XXX
void cNEDNetworkBuilder::addChannelAttr(cChannel *chanp, ChannelAttrNode *channelattr)
{
    const char *attrname = channelattr->getName();
    cPar *p = new cPar(attrname);
    ExpressionNode *valueexpr = findExpression(channelattr, "value");
    *p = evaluateAsLong(valueexpr,NULL,false); // note: this doesn't allow strings or "volatile" values
    chanp->addPar(p);
}
*/

cModuleType *cNEDNetworkBuilder::findAndCheckModuleType(const char *modtypename, cModule *modp, const char *submodname)
{
    cModuleType *modtype = cModuleType::find(modtypename);
    if (!modtype)
        throw new cRuntimeError("dynamic module builder: module type definition `%s' for submodule %s "
                                "in (%s)%s not found (Define_Module() missing from C++ source?)",
                                modtypename, submodname, modp->className(), modp->fullPath().c_str());
    return modtype;
}

void cNEDNetworkBuilder::addSubmodule(cModule *modp, SubmoduleNode *submod)
{
    // create submodule
    const char *submodname = submod->getName();
    std::string submodtypename;
    if (strnull(submod->getLikeParam()))
    {
        submodtypename = submod->getType();
    }
    else
    {
        const char *parname = submod->getLikeParam();
        submodtypename = modp->par(parname).stringValue();
    }

    ExpressionNode *vectorsizeexpr = findExpression(submod, "vector-size");

    if (!vectorsizeexpr)
    {
        cModuleType *submodtype = findAndCheckModuleType(submodtypename.c_str(), modp, submodname);
        cModule *submodp = submodtype->create(submodname, modp);
        ModulePtrVector& v = submodMap[submodname];
        v.push_back(submodp);

        cContextSwitcher __ctx(submodp); // params need to be evaluated in the module's context
        setDisplayString(submodp, submod);
        assignSubmoduleParams(submodp, submod);
        submodp->readInputParams();
        setupGateVectors(submodp, submod);
    }
    else
    {
        int vectorsize = (int) evaluateAsLong(vectorsizeexpr, modp, false);
        ModulePtrVector& v = submodMap[submodname];
        cModuleType *submodtype = NULL;
        for (int i=0; i<vectorsize; i++)
        {
            if (!submodtype)
                submodtype = findAndCheckModuleType(submodtypename.c_str(), modp, submodname);
            cModule *submodp = submodtype->create(submodname, modp, vectorsize, i);
            v.push_back(submodp);

            cContextSwitcher __ctx(submodp); // params need to be evaluated in the module's context
            setDisplayString(submodp, submod);
            assignSubmoduleParams(submodp, submod);
            submodp->readInputParams();
            setupGateVectors(submodp, submod);
        }
    }

    // Note: buildInside() will be called when connections have been built out
    // on this level too.
}


void cNEDNetworkBuilder::setDisplayString(cModule *submodp, SubmoduleNode *submod)
{
/*XXX
    DisplayStringNode *dispstrnode = submod->getFirstDisplayStringChild();
    if (dispstrnode)
    {
        const char *dispstr = dispstrnode->getValue();
        submodp->setDisplayString(dispstr);
    }
*/
}

void cNEDNetworkBuilder::setConnDisplayString(cGate *srcgatep, ConnectionNode *conn)
{
/*XXX
    DisplayStringNode *dispstrnode = conn->getFirstDisplayStringChild();
    if (dispstrnode)
    {
        const char *dispstr = dispstrnode->getValue();
        srcgatep->setDisplayString(dispstr);
    }
*/
}

void cNEDNetworkBuilder::setBackgroundDisplayString(cModule *modp, CompoundModuleNode *mod)
{
/*XXX
    DisplayStringNode *dispstrnode = mod->getFirstDisplayStringChild();
    if (dispstrnode)
    {
        const char *dispstr = dispstrnode->getValue();
        modp->setBackgroundDisplayString(dispstr);
    }
*/
}

void cNEDNetworkBuilder::assignSubmoduleParams(cModule *submodp, NEDElement *submod)
{
    ParametersNode *substparams = (ParametersNode *) submod->getFirstChildWithTag(NED_PARAMETERS);
    if (!substparams)
        return;

    cModule *modp = submodp->parentModule();
    for (ParamNode *par=substparams->getFirstParamChild(); par; par=par->getNextParamNodeSibling())
    {
        // assign param value
        const char *parname = par->getName();
        cPar& p = submodp->par(parname);
//XXX        assignParamValue(p, findExpression(par,"value"),modp,submodp);
    }
}

void cNEDNetworkBuilder::setupGateVectors(cModule *submodp, NEDElement *submod)
{
    GatesNode *gatesizes = (GatesNode *) submod->getFirstChildWithTag(NED_GATES);
    if (!gatesizes)
        return;

    cModule *modp = submodp->parentModule();
    for (GateNode *gate=gatesizes->getFirstGateChild(); gate; gate=gate->getNextGateNodeSibling())
    {
        // set gate vector size
        const char *gatename = gate->getName();
        int vectorsize = (int) evaluateAsLong(findExpression(gate, "vector-size"), submodp, true);
        submodp->setGateSize(gatename, vectorsize);
        //printf("DBG: gatesize: %s.%s[%d]\n", submodp->fullPath().c_str(), gatename, vectorsize);
    }
}

cGate *cNEDNetworkBuilder::getFirstUnusedParentModGate(cModule *modp, const char *gatename)
{
    // same code as generated by nedtool into _n.cc files
    int baseId = modp->findGate(gatename);
    if (baseId<0)
        throw new cRuntimeError("dynamic module builder: %s has no %s[] gate",modp->fullPath().c_str(), gatename);
    int n = modp->gate(baseId)->size();
    for (int i=0; i<n; i++)
        if (!modp->gate(baseId+i)->isConnectedInside())
            return modp->gate(baseId+i);
    throw new cRuntimeError("%s[] gates are all connected, no gate left for `++' operator",modp->fullPath().c_str(), gatename);
}

cGate *cNEDNetworkBuilder::getFirstUnusedSubmodGate(cModule *modp, const char *gatename)
{
    // same code as generated by nedtool into _n.cc files
    int baseId = modp->findGate(gatename);
    if (baseId<0)
        throw new cRuntimeError("dynamic module builder: %s has no %s[] gate",modp->fullPath().c_str(), gatename);
    int n = modp->gate(baseId)->size();
    for (int i=0; i<n; i++)
        if (!modp->gate(baseId+i)->isConnectedOutside())
            return modp->gate(baseId+i);
    int newBaseId = modp->setGateSize(gatename,n+1);
    return modp->gate(newBaseId+n);
}

void cNEDNetworkBuilder::addConnectionGroup(cModule *modp, ConnectionGroupNode *conngroup)
{
    loopVarSP = 0;
    //FIXME maybe it begins with condition?
    doLoop(modp, conngroup->getFirstLoopChild());
}

void cNEDNetworkBuilder::doLoop(cModule *modp, LoopNode *loop)
{
    ConnectionGroupNode *conngroup = (ConnectionGroupNode *) loop->getParent();

    int start = (int) evaluateAsLong(findExpression(loop, "from-value"), modp, false);
    int end = (int) evaluateAsLong(findExpression(loop, "to-value"), modp, false);
    LoopNode *nextloopvar = loop->getNextLoopNodeSibling();

    // register loop var
    if (loopVarSP==MAX_LOOP_NESTING)
        throw new cRuntimeError("dynamic module builder: nesting of for loops is too deep, max %d is allowed", MAX_LOOP_NESTING);
    loopVarSP++;
    loopVarStack[loopVarSP-1].varname = loop->getParamName();
    int& i = loopVarStack[loopVarSP-1].value;

    // do for loop
    if (nextloopvar)
    {
        //FIXME maybe next one is an "if"??
        for (i=start; i<=end; i++)
        {
            // do nested loops
            doLoop(modp, nextloopvar);
        }
    }
    else
    {
        for (i=start; i<=end; i++)
        {
            // do connections
            for (ConnectionNode *conn=conngroup->getFirstConnectionChild(); conn; conn=conn->getNextConnectionNodeSibling())
            {
                addConnection(modp, conn);
            }
        }
    }

    // deregister loop var
    loopVarSP--;
}

void cNEDNetworkBuilder::addConnection(cModule *modp, ConnectionNode *conn)
{
    // check condition first
    ExpressionNode *condexpr = findExpression(conn, "condition");
    if (condexpr && evaluateAsBool(condexpr, modp, false)==false)
        return;

    // find gates and create connection
    cGate *srcg = resolveGate(modp, conn->getSrcModule(), findExpression(conn, "src-module-index"),
                                    conn->getSrcGate(), findExpression(conn, "src-gate-index"),
                                    conn->getSrcGatePlusplus());
    cGate *destg = resolveGate(modp, conn->getDestModule(), findExpression(conn, "dest-module-index"),
                                     conn->getDestGate(), findExpression(conn, "dest-gate-index"),
                                     conn->getDestGatePlusplus());
    cChannel *channel = createChannelForConnection(conn,modp);

    // check directions
    cGate *errg = NULL;
    if (srcg->ownerModule()==modp ? srcg->type()!='I' : srcg->type()!='O')
        errg = srcg;
    if (destg->ownerModule()==modp ? destg->type()!='O' : destg->type()!='I')
        errg = destg;
    if (errg)
        throw new cRuntimeError("dynamic module builder: gate %s in (%s)%s is being "
                                "connected the wrong way: directions don't match",
                                errg->fullPath().c_str(), modp->className(), modp->fullPath().c_str());

    // connect
    if (channel)
        srcg->connectTo(destg, channel);
    else
        srcg->connectTo(destg);

    // display string
    setConnDisplayString(srcg, conn);
}

cGate *cNEDNetworkBuilder::resolveGate(cModule *parentmodp,
                                       const char *modname, ExpressionNode *modindexp,
                                       const char *gatename, ExpressionNode *gateindexp,
                                       bool isplusplus)
{
    if (isplusplus && gateindexp)
        throw new cRuntimeError("dynamic module builder: \"++\" and gate index expression cannot exist together");

    cModule *modp;
    if (strnull(modname))
    {
        modp = parentmodp;
    }
    else
    {
        int modindex = !modindexp ? 0 : (int) evaluateAsLong(modindexp, parentmodp, false);
        modp = _submodule(parentmodp, modname,modindex);
        if (!modp)
        {
            if (!modindexp)
                throw new cRuntimeError("dynamic module builder: submodule `%s' in (%s)%s not found",
                                        modname, parentmodp->className(), parentmodp->fullPath().c_str());
            else
                throw new cRuntimeError("dynamic module builder: submodule `%s[%d]' in (%s)%s not found",
                                        modname, modindex, parentmodp->className(), parentmodp->fullPath().c_str());
        }
    }

    cGate *gatep = NULL;
    if (!gateindexp && !isplusplus)
    {
        gatep = modp->gate(gatename);
        if (!gatep)
            throw new cRuntimeError("dynamic module builder: module (%s)%s has no gate `%s'",
                                    modp->className(), modp->fullPath().c_str(), gatename);
    }
    else if (isplusplus)
    {
        if (modp == parentmodp)
            gatep = getFirstUnusedParentModGate(modp, gatename);
        else
            gatep = getFirstUnusedSubmodGate(modp, gatename);
    }
    else // (gateindexp)
    {
        int gateindex = (int) evaluateAsLong(gateindexp, parentmodp, false);
        gatep = modp->gate(gatename, gateindex);
        if (!gatep)
            throw new cRuntimeError("dynamic module builder: module (%s)%s has no gate `%s[%d]'",
                                    modp->className(), modp->fullPath().c_str(), gatename, gateindex);
    }
    return gatep;
}

cChannel *cNEDNetworkBuilder::createChannelForConnection(ConnectionNode *conn, cModule *parentmodp)
{
/*XXX
    ConnAttrNode *connattr = conn->getFirstConnAttrChild();
    if (!connattr)
        return NULL;

    if (!strcmp(connattr->getName(),"channel"))
    {
        // find channel name
        ExpressionNode *expr = findExpression(connattr,"value");
        ConstNode *cnode = expr->getFirstConstChild();
        if (!cnode || cnode->getType()!=NED_CONST_STRING)
            throw new cRuntimeError("dynamic module builder: channel type should be string constant");
        const char *channeltypename = cnode->getValue();

        // create channel
        cChannelType *channeltype = findChannelType(channeltypename);
        if (!channeltype)
            throw new cRuntimeError("dynamic module builder: channel type %s not found", channeltypename);
        cChannel *channel = channeltype->create("channel");
        return channel;
    }

    // connection attributes
    cBasicChannel *channel = new cBasicChannel();
    for (ConnAttrNode *child=conn->getFirstConnAttrChild(); child; child = child->getNextConnAttrNodeSibling())
    {
        const char *name = child->getName();
        ExpressionNode *expr = findExpression(child,"value");
        cPar *par = new cPar(name);
        assignParamValue(*par, expr, parentmodp,NULL);
        channel->addPar(par);
    }
    return channel;
*/
return new cBasicChannel();
}

ExpressionNode *cNEDNetworkBuilder::findExpression(NEDElement *node, const char *exprname)
{
    // find expression with given name in node
    if (!node)
        return NULL;
    for (NEDElement *child=node->getFirstChildWithTag(NED_EXPRESSION); child; child = child->getNextSiblingWithTag(NED_EXPRESSION))
    {
        ExpressionNode *expr = (ExpressionNode *)child;
        if (!strcmp(expr->getTarget(),exprname))
            return expr;
    }
    return NULL;
}

long cNEDNetworkBuilder::evaluateAsLong(ExpressionNode *exprNode, cComponent *context, bool inSubcomponentScope)
{
    cDynamicExpression *e = cExpressionBuilder().process(exprNode, inSubcomponentScope);
    return e->longValue(context); //FIXME this can be speeded up by caching cDynamicExpressions, and not recreating them every time
}

bool cNEDNetworkBuilder::evaluateAsBool(ExpressionNode *exprNode, cComponent *context, bool inSubcomponentScope)
{
    cDynamicExpression *e = cExpressionBuilder().process(exprNode, inSubcomponentScope);
    return e->boolValue(context);
}
