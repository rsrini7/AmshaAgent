import sys
import re

in_main = False
found_main = False
for line in sys.stdin:
    if line.startswith('"main"'):
        in_main = True
        found_main = True
        print(line, end='')
    elif line.startswith('"') and in_main:
        in_main = False
    elif in_main:
        print(line, end='')

if not found_main:
    print("MAIN THREAD NOT FOUND")
