#!/usr/bin/env python3
"""FQN and Import Cleaner:
1. Converts fully-qualified class names to short names + explicit imports.
2. Expands or removes unused wildcard imports (.*).
3. Strips unused single-type imports that Spotless might miss.

Import sorting and code formatting are subsequently finalized by Spotless (mvn spotless:apply).
"""
import os
import re

fqn_pattern = re.compile(
    r'\b((?:java|jakarta|com|org|dev|io|ai|net)\.(?:[a-z_][\w_]*\.)+([A-Z][\w$]*))\b'
)

# Pre-index all package -> class names in the repository
package_classes = {}


def build_package_index():
    for root, dirs, files in os.walk('.'):
        if 'target' in dirs:
            dirs.remove('target')
        if '.git' in dirs:
            dirs.remove('.git')
        if 'node_modules' in dirs:
            dirs.remove('node_modules')
        for name in files:
            if name.endswith('.java'):
                path = os.path.join(root, name)
                try:
                    with open(path, 'r', encoding='utf-8') as f:
                        for line in f:
                            m = re.match(r'^\s*package\s+([\w\.]+);', line)
                            if m:
                                pkg = m.group(1)
                                cls = name[:-5]  # remove .java
                                if pkg not in package_classes:
                                    package_classes[pkg] = set()
                                package_classes[pkg].add(cls)
                                break
                except Exception:
                    pass


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
    if 'channel-api' in current_path:
        module = 'api'
    elif 'core' in current_path:
        module = 'core'
    if module in ('api', 'core') and fqn.startswith('com.amsha.app.'):
        return True
    return False


def clean_unused_and_wildcards(imports, static_imports, body_text, file_pkg):
    """Filter out unused single-type imports and expand/remove internal wildcard imports."""
    cleaned_imports = set()
    cleaned_static = set()

    for imp in imports:
        if imp.endswith('.*'):
            pkg = imp[:-2]
            if pkg.startswith('com.amsha.'):
                if pkg in package_classes:
                    for cls in package_classes[pkg]:
                        if re.search(r'\b' + re.escape(cls) + r'\b', body_text):
                            if pkg != file_pkg:
                                cleaned_imports.add(f'{pkg}.{cls}')
                else:
                    # Unknown internal package — preserve as fallback
                    cleaned_imports.add(imp)
            else:
                # External package wildcard (e.g. dev.langchain4j, java.util) — always preserve
                cleaned_imports.add(imp)
        else:
            # Single type import: check if simple name is used in body
            simple = imp.split('.')[-1]
            # If the file defines its own class/record/enum/interface with the same simple name, the import is shadowed and unused
            shadow_pattern = (
                r'^[ \t]*(?:(?:public|protected|private|static|final|sealed|non-sealed|abstract)\s+)*'
                r'(?:class|record|interface|@interface|enum)[ \t]+'
                + re.escape(simple)
                + r'\b'
            )
            is_shadowed = bool(re.search(shadow_pattern, body_text, re.MULTILINE))
            if not is_shadowed and re.search(r'\b' + re.escape(simple) + r'\b', body_text):
                imp_pkg = imp[:-(len(simple) + 1)]
                if imp_pkg != file_pkg:
                    cleaned_imports.add(imp)

    for imp in static_imports:
        if imp.endswith('.*'):
            cleaned_static.add(imp)
        else:
            simple = imp.split('.')[-1]
            if re.search(r'\b' + re.escape(simple) + r'\b', body_text):
                cleaned_static.add(imp)

    return cleaned_imports, cleaned_static


def process_file(path):
    """Replace FQNs with short names, expand wildcards, and remove unused imports."""
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    line_ending = '\r\n' if '\r\n' in content else '\n'
    lines = content.splitlines()

    initial_imports = set()
    initial_static_imports = set()
    package_line = None
    file_pkg = None
    body_lines = []

    for line in lines:
        s = line.strip()
        if s.startswith('package '):
            package_line = line
            m = re.match(r'^\s*package\s+([\w\.]+);', s)
            if m:
                file_pkg = m.group(1)
            continue
        elif s.startswith('import static '):
            imp = s[len('import static '):].rstrip(';').strip()
            initial_static_imports.add(imp)
            continue
        elif s.startswith('import '):
            imp = s[len('import '):].rstrip(';').strip()
            initial_imports.add(imp)
            continue
        else:
            body_lines.append(line)

    output_body = []
    modified = False
    in_block_comment = False
    imports = set(initial_imports)
    static_imports = set(initial_static_imports)

    for line in body_lines:
        s = line.strip()

        if in_block_comment:
            if '*/' in line:
                in_block_comment = False
            output_body.append(line)
            continue

        check = re.sub(r'"(?:[^"\\]|\\.)*"', '', line)
        lc = check.find('//')
        if lc != -1:
            check = check[:lc]
        if '/*' in check:
            open_pos = check.find('/*', 0)
            if '*/' not in check[open_pos + 2:]:
                in_block_comment = True
            output_body.append(line)
            continue

        temp_line = line
        for m in reversed(list(fqn_pattern.finditer(temp_line))):
            fqn = m.group(1)
            simple = m.group(2)
            pos = m.start()

            if is_in_line_comment_or_string(temp_line, pos):
                continue
            if '// fully qualified to disambiguate' in temp_line:
                continue
            if should_skip_import(fqn, path):
                continue

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

        output_body.append(temp_line)

    body_text = '\n'.join(output_body)
    cleaned_imports, cleaned_static = clean_unused_and_wildcards(
        imports, static_imports, body_text, file_pkg
    )

    if cleaned_imports != initial_imports or cleaned_static != initial_static_imports:
        modified = True

    if not modified:
        return False

    final_lines = []

    if package_line:
        final_lines.append(package_line)
        final_lines.append('')

    for imp in sorted(cleaned_static):
        final_lines.append(f'import static {imp};')

    for imp in sorted(cleaned_imports):
        final_lines.append(f'import {imp};')

    if cleaned_static or cleaned_imports:
        final_lines.append('')

    final_lines.extend(output_body)

    with open(path, 'w', encoding='utf-8') as f:
        f.write(line_ending.join(final_lines) + line_ending)

    return True


def main():
    build_package_index()
    modified_count = 0
    for root, dirs, files in os.walk('.'):
        if 'target' in dirs:
            dirs.remove('target')
        if '.git' in dirs:
            dirs.remove('.git')
        if 'node_modules' in dirs:
            dirs.remove('node_modules')
        for name in files:
            if name.endswith('.java'):
                path = os.path.join(root, name)
                if process_file(path):
                    modified_count += 1

    print(f'FQN & Import replacement: {modified_count} files modified. Formatting finalized by Spotless.')


if __name__ == '__main__':
    main()

