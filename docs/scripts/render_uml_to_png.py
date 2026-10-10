import os
import sys
import zlib
import urllib.request

def encode_plantuml(text):
    def encode6bit(b):
        if b < 10: return chr(48 + b)
        b -= 10
        if b < 26: return chr(65 + b)
        b -= 26
        if b < 26: return chr(97 + b)
        b -= 26
        if b == 0: return '-'
        if b == 1: return '_'
        return '?'

    def append3bytes(b1, b2, b3):
        c1 = b1 >> 2
        c2 = ((b1 & 0x3) << 4) | (b2 >> 4)
        c3 = ((b2 & 0xF) << 2) | (b3 >> 6)
        c4 = b3 & 0x3F
        return encode6bit(c1 & 0x3F) + encode6bit(c2 & 0x3F) + encode6bit(c3 & 0x3F) + encode6bit(c4 & 0x3F)

    data = text.encode('utf-8')
    compressed = zlib.compress(data)[2:-4]
    res = ''
    for i in range(0, len(compressed), 3):
        chunk = compressed[i:i+3]
        if len(chunk) == 3:
            res += append3bytes(chunk[0], chunk[1], chunk[2])
        elif len(chunk) == 2:
            res += append3bytes(chunk[0], chunk[1], 0)[:3]
        elif len(chunk) == 1:
            res += append3bytes(chunk[0], 0, 0)[:2]
    return '~1' + res

def render_file(puml_path, output_png_path):
    print(f"Rendering {puml_path} -> {output_png_path}...")
    with open(puml_path, 'r', encoding='utf-8') as f:
        content = f.read()

    encoded = encode_plantuml(content)
    url = f"https://www.plantuml.com/plantuml/png/{encoded}"
    
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            png_bytes = resp.read()
            with open(output_png_path, 'wb') as out_f:
                out_f.write(png_bytes)
            print(f"  [SUCCESS] Written {len(png_bytes):,} bytes to {output_png_path}")
            return True
    except Exception as e:
        print(f"  [ERROR] Failed to render {puml_path}: {e}")
        return False

def main():
    uml_dir = os.path.join(os.path.dirname(__file__), "..", "uml")
    uml_dir = os.path.abspath(uml_dir)
    
    # Priority targets requested by user
    priority_files = [
        "placeholder_service_desk.puml",
        "placeholder_rag_funnel.puml",
        "placeholder_hcmute.puml"
    ]
    
    # Check if user specified --all
    if len(sys.argv) > 1 and sys.argv[1] == "--all":
        puml_files = [f for f in os.listdir(uml_dir) if f.endswith(".puml")]
        for f in sorted(puml_files):
            p_path = os.path.join(uml_dir, f)
            o_path = os.path.splitext(p_path)[0] + ".png"
            if not os.path.exists(o_path):
                render_file(p_path, o_path)
        return

    # Check if user specified a file
    if len(sys.argv) > 1:
        target = sys.argv[1]
        p_path = os.path.join(uml_dir, target)
        o_path = os.path.splitext(p_path)[0] + ".png"
        render_file(p_path, o_path)
        return

    # Render priority targets first
    for f in priority_files:
        p_path = os.path.join(uml_dir, f)
        if os.path.exists(p_path):
            o_path = os.path.splitext(p_path)[0] + ".png"
            render_file(p_path, o_path)

if __name__ == '__main__':
    main()
