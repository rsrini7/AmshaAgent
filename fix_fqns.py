import os
import re

# Matches FQNs rooted at java/jakarta/com/org/dev/io — covers Quarkus, Vert.x, SmallRye, etc.
fqn_pattern = re.compile(
    r'\b((?:java|jakarta|com|org|dev|io)\.(?:[a-z_][\w_]*\.)+([A-Z][\w$]*))\b'
)


def is_in_line_comment_or_string(line, pos):
    """Return True if pos falls inside a // comment or a string literal on the given line."""
    # Neutralize escaped quotes so they don't confuse quote-counting
    clean = line.replace('\\"', '__').replace("\\'", '__')

    # ── Step 1: find where the // comment starts (skipping // inside strings) ──
    comment_start = len(clean)
    in_str = False
    for i, ch in enumerate(clean):
        if ch == '"':
            in_str = not in_str
        if not in_str and ch == '/' and i + 1 < len(clean) and clean[i + 1] == '/':
            comment_start = i
            break

    if pos >= comment_start:
        return True  # inside // comment

    # ── Step 2: check whether pos is inside a string literal ──
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


def get_module_name(path):
    if 'arcclaw-channel-api' in path: return 'api'
    if 'arcclaw-core'        in path: return 'core'
    if 'arcclaw-app'         in path: return 'app'
    if 'arcclaw-deployment'  in path: return 'deployment'
    return 'unknown'


def should_skip_import(fqn, current_path):
    """Block downward-dependency imports (api/core must not import app classes)."""
    module = get_module_name(current_path)
    if module in ('api', 'core') and fqn.startswith('com.arcclaw.app.'):
        return True
    return False


def is_redundant_java_lang(imp):
    """java.lang.String, java.lang.Integer, etc. are auto-imported — skip them."""
    if imp.startswith('java.lang.'):
        return '.' not in imp.replace('java.lang.', '', 1)
    return False


def organize_imports(lines, imports):
    """Rebuild file with sorted/grouped imports, preserving pre-package headers."""

    def get_cat(x):
        if x.startswith('static '):  return 0
        if x.startswith('java.'):    return 1
        if x.startswith('javax.'):   return 2
        if x.startswith('jakarta.'): return 3
        return 4

    final_imps = {imp for imp in imports if not is_redundant_java_lang(imp)}
    sorted_imps = sorted(final_imps, key=lambda x: (get_cat(x), x))

    # ── Partition lines into: pre-package header | package | body ──
    pre_package, body = [], []
    package_line = ""
    found_package = False

    for l in lines:
        s = l.strip()
        if not found_package:
            if s.startswith('package '):
                found_package = True
                package_line = l
            else:
                pre_package.append(l)
        else:
            if s.startswith('import '):
                continue          # discard originals; regenerated below
            body.append(l)

    # Drop trailing blank lines from copyright block so we control spacing
    while pre_package and not pre_package[-1].strip():
        pre_package.pop()

    output = []
    if pre_package:
        output.extend(pre_package)
        output.append('')
    if package_line:
        output.append(package_line)
        output.append('')

    prev_cat = -1
    for imp in sorted_imps:
        curr_cat = get_cat(imp)
        if prev_cat != -1 and curr_cat != prev_cat:
            output.append('')
        output.append(f'import {imp};')
        prev_cat = curr_cat

    if sorted_imps:
        output.append('')

    # Re-attach body, skipping any leading blank lines
    started = False
    for l in body:
        if not started and not l.strip():
            continue
        started = True
        output.append(l)

    return output


def process_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    line_ending = '\r\n' if '\r\n' in content else '\n'
    lines = content.splitlines()
    body_lines = []
    imports = set()
    modified = False
    in_block_comment = False

    # ── Pass 1: collect all existing imports ──
    for line in lines:
        s = line.strip()
        if s.startswith('import '):
            imp = s[len('import '):].rstrip(';').strip()
            imports.add(imp)

    # ── Pass 2: FQN replacement with full comment/string awareness ──
    for line in lines:
        s = line.strip()

        if s.startswith('package ') or s.startswith('import '):
            body_lines.append(line)
            continue

        # ── Block-comment state machine ──
        if in_block_comment:
            if '*/' in line:
                in_block_comment = False
            body_lines.append(line)
            continue

        # Detect /* that opens a new block comment.
        # Strip string literals and // tail so /* inside them doesn't confuse us.
        check = re.sub(r'"(?:[^"\\]|\\.)*"', '', line)
        lc = check.find('//')
        if lc != -1:
            check = check[:lc]
        if '/*' in check:
            open_pos = check.find('/*')
            if '*/' not in check[open_pos + 2:]:
                in_block_comment = True
            body_lines.append(line)   # pass through without FQN processing
            continue

        # ── Regular code line: replace FQNs ──
        temp_line = line
        for m in reversed(list(fqn_pattern.finditer(temp_line))):
            fqn    = m.group(1)
            simple = m.group(2)
            pos    = m.start()

            if is_in_line_comment_or_string(temp_line, pos): continue
            if '// fully qualified to disambiguate' in line:  continue
            if should_skip_import(fqn, path):                 continue

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

        body_lines.append(temp_line)

    if not modified:
        # No FQN changes, but still re-organize imports if needed
        reorganized = organize_imports(lines, imports)
        if reorganized != lines:
            with open(path, 'w', encoding='utf-8') as f:
                f.write(line_ending.join(reorganized) + line_ending)
            return True
        return False

    output = organize_imports(body_lines, imports)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(line_ending.join(output) + line_ending)
    return True


# ── Entry point ──
modified_count = 0
for root, dirs, files in os.walk('.'):
    if 'target' in dirs: dirs.remove('target')
    if '.git'   in dirs: dirs.remove('.git')
    for name in files:
        if name.endswith('.java'):
            path = os.path.join(root, name)
            if process_file(path):
                modified_count += 1

print(f'Processed and organized {modified_count} files.')
