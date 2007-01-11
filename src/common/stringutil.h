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
#include "commonutil.h"


/**
 * Reverse of opp_quotestr(): remove quotes and resolve backslashed escapes.
 *
 * Throws an exception if there's a parse error. If there's anything
 * (non-whitespace) in the input after the string literal, endp is set to
 * that character; otherwise endp is set to point to the terminating zero
 * of the string.
 */
std::string opp_parsequotedstr(const char *txt, const char *&endp);

/**
 * Reverse of opp_quotestr(): remove quotes and resolve backslashed escapes.
 *
 * Throws an exception if there's a parse error.
 */
std::string opp_parsequotedstr(const char *txt);

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
    return opp_needsquotes(txt) ? opp_quotestr(txt) : std::string(txt);
}

/**
 * A macro version of opp_quotestr_ifneeded(). This is more efficient,
 * because it avoids conversion to std::string when no quoting is needed.
 */
#define QUOTE(txt)   (opp_needsquotes(txt) ? opp_quotestr(txt).c_str() : (txt))

/**
 * Create a string using printf-like formatting.
 */
std::string opp_stringf(const char *fmt, ...);

/**
 * Create a string using printf-like formatting.
 */
std::string opp_vstringf(const char *fmt, va_list& args);

/**
 * Dictionary-compare two strings, the main difference from stricmp()
 * being that integers embedded in the strings are compared in
 * numerical order.
 */
int strdictcmp(const char *s1, const char *s2);

#endif


