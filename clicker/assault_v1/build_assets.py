"""从同源历史截图生成已登记模板；仅开发时执行，运行时不依赖历史目录。"""
import json
import cv2
from . import config


def main():
    config.ASSETS.mkdir(exist_ok=True)
    manifest = {}
    for name, (source, box) in config.TEMPLATES.items():
        path = (config.ROOT / source[2:] if source.startswith('@/') else
                config.ROOT / 'clicker/_sim/t022' / (source + '.png'))
        frame = cv2.imread(str(path))
        if frame is None:
            raise FileNotFoundError(path)
        h, w = frame.shape[:2]
        a, b, c, d = box
        crop = frame[int(b*h):int(d*h), int(a*w):int(c*w)]
        assert cv2.imwrite(str(config.template_path(name)), crop)
        manifest[name] = {'source': str(path.relative_to(config.ROOT)), 'box': box}
    config.MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print('ASSETS=' + str(len(manifest)))


if __name__ == '__main__':
    main()
