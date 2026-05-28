"""FQN replacement: converts fully-qualified class names to short names + imports.

Import sorting/formatting is handled by Spotless (mvn spotless:apply).
This script ONLY does FQN → short name replacement and adds the import.
"""
import os
import re

fqn_pattern = re.compile(
    r'\b((?:java|jakarta|com|org|dev|io)\.(?:[a-z_][\w_]*\.)+([A-Z][\w$]*))\b'
)


def is_in_line_comment_or_string(line, pos):
    """Return True if pos falls inside a // comment or a string literal."""
    clean = line.replace('\\"', '__').replace("\\'", '__')
    comment_start = len(clean)
    in_str = False
    for i, ch in enumerate(clean):
        if ch == '"':
            in_str = not in_str
        if not in_str and ch == '/' and i + 1 < len(clean) and clean[i + 1] == '/':
            comment_start = i
            break
    if pos >= comment_start:
        return True
    in_str = False
    str_start = -1
    for i, ch in enumerate(clean[:comment_start]):
        if ch == '"':
            if not in_str:
                in_str = True
                str_start = i
            else:
                if str_start < pos < i:
                    return True
                in_str = False
    if in_str and pos > str_start:
        return True
    return False


def should_skip_import(fqn, current_path):
    """Block downward-dependency imports (api/core must not import app classes)."""
    module = 'unknown'
    if 'arcclaw-channel-api' in current_path: module = 'api'
    elif 'arcclaw-core' in current_path: module = 'core'
    if module in ('api', 'core') and fqn.startswith('com.arcclaw.app.'):
        return True
    return False


def process_file(path):
    """Replace FQNs with short names and add imports. Returns True if modified."""
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    line_ending = '\r\n' if '\r\n' in content else '\n'
    lines = content.splitlines()
    imports = set()
    output = []
    modified = False
    in_block_comment = False

    # Collect existing imports
    for line in lines:
        s = line.strip()
        if s.startswith('import '):
            imp = s[len('import '):].rstrip(';').strip()
            imports.add(imp)

    # FQN replacement pass
    for line in lines:
        s = line.strip()

        if s.startswith('package ') or s.startswith('import '):
            output.append(line)
            continue

        if in_block_comment:
            if '*/' in line:
                in_block_comment = False
            output.append(line)
            continue

        check = re.sub(r'"(?:[^"\\]|\\.)*"', '', line)
        lc = check.find('//')
        if lc != -1:
            check = check[:lc]
        if '/*' in check:
            open_pos = check.find('/*')
            if '*/' not in check[open_pos + 2:]:
                in_block_comment = True
            output.append(line)
            continue

        temp_line = line
        for m in reversed(list(fqn_pattern.finditer(temp_line))):
            fqn = m.group(1)
            simple = m.group(2)
            pos = m.start()

            if is_in_line_comment_or_string(temp_line, pos):
                continue
            if '// fully qualified to disambiguate' in line:
                continue
            if should_skip_import(fqn, path):
                continue

            # Keep Caffeine internals fully qualified
            if 'caffeine.cache.' in fqn and not simple.startswith('Caffeine'):
                if '// fully qualified to disambiguate' not in temp_line:
                    temp_line += ' // fully qualified to disambiguate'
                continue

            collision = any(
                ex.endswith('.' + simple) and ex != fqn
                for ex in imports
            )

            if collision:
                if '// fully qualified to disambiguate' not in temp_line:
                    temp_line += ' // fully qualified to disambiguate'
            else:
                temp_line = temp_line[:m.start()] + simple + temp_line[m.end():]
                imports.add(fqn)
                modified = True

        output.append(temp_line)

    if not modified:
        return False

    with open(path, 'w', encoding='utf-8') as f:
        f.write(line_ending.join(output) + line_ending)
    return True


# Entry point
modified_count = 0
for root, dirs, files in os.walk('.'):
    if 'target' in dirs:
        dirs.remove('target')
    if '.git' in dirs:
        dirs.remove('.git')
    for name in files:
        if name.endswith('.java'):
            path = os.path.join(root, name)
            if process_file(path):
                modified_count += 1

print(f'FQN replacement: {modified_count} files modified. Import sorting handled by Spotless.')
