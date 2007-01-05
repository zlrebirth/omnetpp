//=========================================================================
//  FORCEDIRECTEDPARAMETERSBASE.H - part of
//                  OMNeT++/OMNEST
//           Discrete System Simulation in C++
//
//=========================================================================

/*--------------------------------------------------------------*
  Copyright (C) 1992-2006 Andras Varga

  This file is distributed WITHOUT ANY WARRANTY. See the file
  `license' for details on this and other legal matters.
*--------------------------------------------------------------*/

#ifndef __FORCEDIRECTEDPARAMETERSBASE_H_
#define __FORCEDIRECTEDPARAMETERSBASE_H_

#include <math.h>
#include "geometry.h"

class ForceDirectedEmbedding;

/**
 * Base class for things that have position.
 */
class IPositioned {
    public:
        virtual Pt getPosition() = 0;
};

/**
 * A variable used in the differential equation.
 * The actual value of the variable is the position, the first derivative is the velocity
 * and the second derivative is the acceleration.
 */
class Variable : public IPositioned {
    protected:
        /**
         * Value of the variable.
         */
	    Pt position;

        /**
         * First derivative.
         */
	    Pt velocity;

        /**
         * Second derivative.
         */
        Pt acceleration;

        /**
         * Total applied force.
         */
        Pt force;

        /**
         * The list of all applied forces for debug purposes.
         */
	    std::vector<Pt> forces;

        /**
         * Total mass of bodies appling forces to this variable.
         */
	    double mass;

    private:
        void constructor(const Pt& position, const Pt& velocity) {
		    this->position = position;
		    this->velocity = velocity;

            mass = 0;
            force = Pt::getZero();
        }
	
    public:
	    Variable(const Pt& position) {
            constructor(position, Pt::getZero());
	    }

	    Variable(const Pt& position, const Pt& velocity) {
            constructor(position, velocity);
	    }

	    virtual Pt getPosition() {
		    return position;
	    }

	    virtual void assignPosition(const Pt& position) {
		    this->position.assign(position);
	    }

	    Pt getVelocity() {
		    return velocity;
	    }

	    virtual void assignVelocity(const Pt& velocity) {
		    this->velocity.assign(velocity);
	    }

	    virtual Pt getAcceleration() {
		    return acceleration.assign(force).divide(mass);
	    }

        double getKineticEnergy() {
            double vlen = velocity.getLength();
            return 0.5 * mass * vlen * vlen;
        }

	    void resetForce() {
            force = Pt::getZero();
	    }

	    double getMass() {
		    return mass;
	    }
    	
	    void addMass(double mass) {
		    this->mass += mass;
	    }

        Pt getForce() {
            return force;
        }

	    void addForce(const Pt& vector, double power, bool inspected = false) {
		    Pt f(vector);

            if (!f.isZero() && f.isFullySpecified()) {
                f.normalize().multiply(power);
		        force.add(f);

                if (inspected)
    		        forces.push_back(f);
            }
	    }

	    void resetForces() {
		    forces.clear();
	    }
    	
	    const std::vector<Pt>& getForces() {
		    return forces;
	    }
};

/**
 * A variable which has fix x and y coordinates but still has a free z coordinate.
 */
class PointConstrainedVariable : public Variable {
    public:
        PointConstrainedVariable(Pt position) : Variable(position) {
        }

	    virtual void assignPosition(const Pt& position) {
            this->position.z = position.z;
        }

	    virtual void assignVelocity(const Pt& velocity) {
            this->velocity.z = velocity.z;
        }

	    virtual Pt getAcceleration() {
		    return acceleration.assign(0, 0, force.z).divide(mass);
	    }
};

/**
 * Interface class for bodies.
 */
class IBody : public IPositioned {
    public:
        virtual const char *getClassName() = 0;

        virtual Rs& getSize() = 0;

        virtual double getMass() = 0;

        virtual double getCharge() = 0;

        virtual Variable *getVariable() = 0;
};

/**
 * Interface class used by the force directed embedding to generate forces among bodies.
 */
class IForceProvider {
    protected:
        ForceDirectedEmbedding *embedding;

        double maxForce;

    public:
        IForceProvider() {
            maxForce = 1000;
        }

        virtual void setForceDirectedEmbedding(ForceDirectedEmbedding *embedding) {
            this->embedding = embedding;
        }

	    double getMaxForce() {
		    return maxForce;
	    }
    	
	    double getValidForce(double force) {
		    ASSERT(force >= 0);
            return std::min(maxForce, force);
	    }
    	
	    double getValidSignedForce(double force) {
            if (force < 0)
                return -getValidForce(fabs(force));
            else
                return getValidForce(fabs(force));
	    }

        Pt getStandardDistanceAndVector(IBody *body1, IBody *body2, double &distance) {
            Pt vector = Pt(body1->getPosition()).subtract(body2->getPosition());
            distance = vector.getLength();
            return vector;
        }

        // TODO: allow infinite sizes and calculate distance by that?
        Pt getStandardHorizontalDistanceAndVector(IBody *body1, IBody *body2, double &distance) {
            Pt vector = Pt(body1->getPosition()).subtract(body2->getPosition());
            vector.y = 0;
            vector.z = 0;
            distance = vector.getLength();
            return vector;
        }

        Pt getStandardVerticalDistanceAndVector(IBody *body1, IBody *body2, double &distance) {
            Pt vector = Pt(body1->getPosition()).subtract(body2->getPosition());
            vector.x = 0;
            vector.z = 0;
            distance = vector.getLength();
            return vector;
        }

        Pt getSlipperyDistanceAndVector(IBody *body1, IBody *body2, double &distance) {
            Rc rc1 = Rc::getRcFromCenterSize(body1->getPosition(), body1->getSize());
            Rc rc2 = Rc::getRcFromCenterSize(body2->getPosition(), body2->getSize());
            Ln ln = rc1.getBasePlaneProjectionDistance(rc2, distance);
            Pt vector = ln.begin;
            vector.subtract(ln.end);
            vector.setNaNToZero();
            return vector;
        }

        virtual const char *getClassName() = 0;

        virtual void applyForces() = 0;

        virtual double getPotentialEnergy() = 0;
};

#endif
