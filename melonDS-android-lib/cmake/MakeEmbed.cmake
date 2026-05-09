# MakeEmbed.cmake - Script to embed a file as a C header array
# Usage: cmake -D INPUT_FILE=input.txt -D OUTPUT_FILE=output.h -D VAR_NAME=array_name -P MakeEmbed.cmake

# Read the input file
file(READ ${INPUT_FILE} FILE_CONTENTS)

# Convert the file contents to a C string literal
string(REPLACE "\\" "\\\\" ESCAPED_CONTENT "${FILE_CONTENTS}")
string(REPLACE "\"" "\\\"" ESCAPED_CONTENT "${ESCAPED_CONTENT}")
string(REPLACE "\n" "\\n\"\n\"" ESCAPED_CONTENT "${ESCAPED_CONTENT}")

# Create the header file content
set(HEADER_CONTENT "/* Auto-generated header from ${INPUT_FILE} */\n")
set(HEADER_CONTENT "${HEADER_CONTENT}#pragma once\n\n")
set(HEADER_CONTENT "${HEADER_CONTENT}static const char ${VAR_NAME}[] =\n")
set(HEADER_CONTENT "${HEADER_CONTENT}\"${ESCAPED_CONTENT}\";\n")

# Write the header file
file(WRITE ${OUTPUT_FILE} "${HEADER_CONTENT}")
