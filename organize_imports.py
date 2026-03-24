import os


def get_import_category(imp):
    if imp.startswith('static '):  return 0
    if imp.startswith('java.'):    return 1
    if imp.startswith('javax.'):   return 2
    if imp.startswith('jakarta.'): return 3
    return 4


def is_redundant_java_lang(imp):
    """java.lang.String / java.lang.Integer etc. are auto-imported — strip them."""
    if imp.startswith('java.lang.'):
        return '.' not in imp.replace('java.lang.', '', 1)
    return False


def organize_imports(lines):
    imports = set()
    package_line = ""
    pre_package = []
    body = []
    found_package = False

    for line in lines:
        stripped = line.strip()
        if not found_package:
            if stripped.startswith('package '):
                found_package = True
                package_line = line
            else:
                pre_package.append(line)    # copyright / license header
        else:
            if stripped.startswith('import '):
                full_import = stripped[len('import '):].rstrip(';').strip()
                if not is_redundant_java_lang(full_import):
                    imports.add(full_import)
            else:
                body.append(line)

    # If no package found at all, return unchanged (e.g. module-info.java edge case)
    if not found_package:
        return lines

    # Remove trailing blank lines from copyright block so we control spacing
    while pre_package and not pre_package[-1].strip():
        pre_package.pop()

    sorted_imps = sorted(imports, key=lambda x: (get_import_category(x), x))

    new_lines = []

    if pre_package:
        new_lines.extend(pre_package)
        new_lines.append('')

    new_lines.append(package_line)
    new_lines.append('')

    prev_cat = -1
    for imp in sorted_imps:
        curr_cat = get_import_category(imp)
        if prev_cat != -1 and curr_cat != prev_cat:
            new_lines.append('')
        new_lines.append(f'import {imp};')
        prev_cat = curr_cat

    if sorted_imps:
        new_lines.append('')

    # Re-attach body, skipping any leading blank lines
    started = False
    for l in body:
        if not started and not l.strip():
            continue
        started = True
        new_lines.append(l)

    return new_lines


def process_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    # Preserve original line endings (CRLF on Windows, LF on Unix)
    line_ending = '\r\n' if '\r\n' in content else '\n'
    lines = content.splitlines()

    new_lines = organize_imports(lines)

    new_content = line_ending.join(new_lines) + line_ending
    if new_content != content:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        return True
    return False


# ── Entry point ──
modified_files = []
for root, dirs, files in os.walk('.'):
    if 'target' in dirs: dirs.remove('target')
    if '.git'   in dirs: dirs.remove('.git')
    for name in files:
        if name.endswith('.java'):
            path = os.path.join(root, name)
            if process_file(path):
                modified_files.append(path)

print(f'Organized imports for {len(modified_files)} files.')
