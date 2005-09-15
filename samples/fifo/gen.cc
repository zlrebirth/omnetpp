//
// This file is part of an OMNeT++/OMNEST simulation example.
//
// Copyright (C) 1992-2005 Andras Varga
//
// This file is distributed WITHOUT ANY WARRANTY. See the file
// `license' for details on this and other legal matters.
//


#include <omnetpp.h>


class FFGenerator : public cSimpleModule
{
  private:
    cMessage *sendMessageEvent;

  public:
    FFGenerator() {}   //NEWCTOR

    virtual void initialize();
    virtual void handleMessage(cMessage *msg);
};

Define_Module(FFGenerator);

void FFGenerator::initialize()
{
    sendMessageEvent = new cMessage("sendMessageEvent");
    scheduleAt(0.0, sendMessageEvent);
}

void FFGenerator::handleMessage(cMessage *msg)
{
    ASSERT(msg==sendMessageEvent);

    cMessage *m = new cMessage("job");
    m->setLength(par("msgLength"));
    send(m, "out");

    scheduleAt(simTime()+(double)par("sendIaTime"), sendMessageEvent);
}


