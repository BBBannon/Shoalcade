# ShoalcadePlayer: turns a binary file into a C array at build time (part of Shoalcade).
# SPDX-License-Identifier: GPL-3.0-or-later
#   cmake -DIN=file.ttf -DOUT=file.c -DNAME=sc_font_regular -P embed.cmake
file(READ "${IN}" hex HEX)
string(LENGTH "${hex}" n)
math(EXPR size "${n} / 2")
string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," body "${hex}")
string(REGEX REPLACE "((0x..,){32})" "\\1\n" body "${body}")
file(WRITE "${OUT}" "/* Generated from ${IN} by cmake/embed.cmake. Do not edit. */\n"
  "const unsigned char ${NAME}[] = {\n${body}\n};\nconst unsigned int ${NAME}_size = ${size};\n")
