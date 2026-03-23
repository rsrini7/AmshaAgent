import os

def get_import_category(imp):
    if imp.startswith('static '): return 0
    if imp.startswith('java.'): return 1
    if imp.startswith('javax.'): return 2
    if imp.startswith('jakarta.'): return 3
    return 4

def organize_imports(lines):
    imports = set()
    other_lines = []
    package_line = ""
    in_header = True
    
    for line in lines:
        stripped = line.strip()
        if not stripped and in_header:
            continue
        if stripped.startswith('package '):
            package_line = line
        elif stripped.startswith('import '):
            full_import = stripped.replace('import ', '').replace(';', '').strip()
            imports.add(full_import)
        elif stripped:
            in_header = False
            other_lines.append(line)
        else:
            if not in_header:
                other_lines.append(line)

    if not imports:
        return lines

    sorted_imps = sorted(list(imports), key=lambda x: (get_import_category(x), x))
    
    new_lines = []
    if package_line:
        new_lines.append(package_line)
        new_lines.append("")
    
    prev_cat = -1
    for imp in sorted_imps:
        curr_cat = get_import_category(imp)
        if prev_cat != -1 and curr_cat != prev_cat:
            new_lines.append("")
        new_lines.append(f"import {imp};")
        prev_cat = curr_cat
    
    if sorted_imps:
        new_lines.append("")
        
    new_lines.extend(other_lines)
    return new_lines

def process_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    lines = content.splitlines()
    new_lines = organize_imports(lines)
    
    new_content = '\n'.join(new_lines) + '\n'
    if new_content != content:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        return True
    return False

modified_files = []
for root, dirs, files in os.walk('.'):
    if 'target' in dirs: dirs.remove('target')
    if '.git' in dirs: dirs.remove('.git')
    for name in files:
        if name.endswith('.java'):
            path = os.path.join(root, name)
            if process_file(path):
                modified_files.append(path)

print(f"Organized imports for {len(modified_files)} files.")
