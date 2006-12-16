//=========================================================================
//  STRINGUTIL.H - part of
//                  OMNeT++/OMNEST
//           Discrete System Simulation in C++
//
//=========================================================================

/*--------------------------------------------------------------*
  Copyright (C) 1992-2005 Andras Varga

  This file is distributed WITHOUT ANY WARRANTY. See the file
  `license' for details on this and other legal matters.
*--------------------------------------------------------------*/

#ifndef _STRINGUTIL_H_
#define _STRINGUTIL_H_

#include <string>
#include "util.h"


/**
 * Reverse of opp_quotestr(): remove quotes and resolve backslashed escapes.
 *
 * Returns a new string allocated via new char[], which has to be deallocated
 * by the caller.
 */
char *opp_parsequotedstr(const char *txt, const char *&endp);

/**
 * Surround the given string with "quotes", also escape with backslash
 * where needed.
 */
std::string opp_quotestr(const char *txt);

/**
 * Returns true if the string contains space, backslash, quote, or anything
 * else that would make quoting (opp_quotestr()) necessary before writing
 * it into a data file.
 */
bool opp_needsquotes(const char *txt);

/**
 * Combines opp_needsquotes() and opp_quotestr().
 */
inline std::string opp_quotestr_ifneeded(const char *txt)
{
    if (opp_needsquotes(txt))
        return opp_quotestr(txt);
    else
        return txt;
}

/**
 * Dictionary-compare two strings, the main difference from stricmp()
 * being that integers embedded in the strings are compared in
 * numerical order.
 */
int strdictcmp(const char *s1, const char *s2);


#endif


