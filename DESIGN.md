# 余烬档案馆

单手换档案、双手使用平板的游戏伴侣。核心是一个按时间排列的档案柜，三处入口分别通往存档、模组和指南。手机采用底部导航，平板横屏采用左侧导航和分栏。

近黑炭色 `#151111` 铺底，木棕表面 `#211918`，陈金 `#E1AB71` 指示选中和关键操作，暗红 `#6B302A` 标识主要按钮。标题使用 Noto Serif SC 的中文字形，正文使用系统字体。线形图标取自书页、封印、匣子和提灯。原生文件选择器处理跨应用文件授权。

插画 `app/src/main/res/drawable-nodpi/archive_illustration.png` 使用内置 ImageGen 生成，提示词为原创中世纪地下档案室、右侧铁艺提灯、木刻和炭笔的刻痕、炭黑和旧铜色、左侧保留深色文字区，画面不含现有游戏素材。

```text
Create a wide landscape raster illustration for the upper header of a native Android gothic dungeon save manager, original art. Inside a ruined medieval stone archive, a solitary wrought-iron lantern with a hot ochre flame sits on a stone plinth at the right third, framed by massive Gothic arch ribs and thick dark masonry, with a few scattered parchment folios near the base. Strong antique woodcut hatching and scratched charcoal texture, sharp graphic shadows, old copper and ember red details. Near-black warm charcoal base #15100f. Left 52% intentionally almost empty and extremely dark with subtle stone grain for overlaid app headings. Keep the key lantern and arch in the center horizontal band for cropping wide and on phones. Atmosphere from restrained copper light; definite physical ink strokes and no neon or glow bloom. No people, no symbols from existing games, no logos, no lettering, no UI, no watermark. Wide 3:2.
```

## 在线工坊美术

v2.3.1 使用内置 ImageGen 生成下列五项原创画面。头图保留左侧文字空间，小件保留透明度，打包为 Android WebP 资源。横屏将搜索栏与排序选项排在同一行，金棕色标示选中项，暗红色标示主操作。

### 创意工坊头图

[workshop_header.webp](app/src/main/res/drawable-nodpi/workshop_header.webp)

```text
Use case: stylized-concept. Original panoramic background artwork for the Android Torch Archive workshop header, a gothic medieval mod library. Hand-engraved copperplate and rough charcoal, tactile and restrained. Very wide landscape 3:1. On the right third, a deep pointed arch doorway and a narrow cabinet of rolled maps lit by one hooded ochre lantern, with one copper-red wax seal in the foreground. Keep the left 60% very dark near charcoal brown (#151111) with only soft texture for white overlay text, keep the main right subject in the middle 40% height for narrow phone/tablet header crops. Tonal detail around the right object, subdued mature palette of smoked brown and old gold. Original setting and props. No people, no logos, no game assets, no lettering, no borders.
```

### 公开 ZIP 头图

[workshop_dispatch.webp](app/src/main/res/drawable-nodpi/workshop_dispatch.webp)

```text
Use case: stylized-concept. Original wide 3:1 landscape small banner artwork for a fantasy Android archive visual theme, to sit behind white Chinese interface text on the left. A squat iron-bound parcel box, folded cloth and a wound copper chain on a stone shelf to the far right, warmed by a sliver of side light. Dark charred wood and antique copperplate hatch work with restrained amber accents. The left 63% should stay near-black charcoal and unoccupied, with a smooth gradual transition into the print texture. Keep important objects around the right-center band for aggressive vertical cropping. No textual marks, logos, characters, existing game assets, borders, or watermark.
```

### 模组默认封面

[workshop_placeholder.webp](app/src/main/res/drawable-nodpi/workshop_placeholder.webp)

```text
Use case: stylized-concept. Square fallback artwork for mod cover thumbnails at 90dp, original object still life. A single closed, weathered dark leather folio with a simple oval red wax seal ringed by aged brass and two understated metal corners, viewed from slightly above. Central 58% silhouette framed by plain smoked dark brown. Woodcut plus charcoal, fine chiseled hatching, a small hard warm highlights, generous negative space, excellent readability at tiny scale. More quiet than real mod images. No text, letters, emblems, runes, brands, game assets, people, or watermark.
```

### 下载完成印章

[workshop_status.webp](app/src/main/res/drawable-nodpi/workshop_status.webp)

```text
Use case: stylized-concept. A single small original Android archive status ornament on a genuinely TRANSPARENT background. A round, worn bronze wax-seal medallion with a simple parchment-colored check mark deeply engraved at the center, dark burnt-umber patina around the edge, copperplate cut marks and highly readable silhouette at 28dp. Strong clean geometry and centered, generous transparent margin around all sides. The only glyph is the simple check mark. No scene, cast shadow, text, labels, existing game art, or watermark.
```

### 无搜索结果插画

[workshop_empty.webp](app/src/main/res/drawable-nodpi/workshop_empty.webp)

```text
Use case: stylized-concept. A single small original Android archive empty-state illustration on a genuinely TRANSPARENT background. An antique bronze magnifying glass resting on a folded blank parchment fragment. Understated charcoal and etched copperplate hatching, a warm amber highlight, clean distinct silhouettes, three-quarter overhead angle, centered with generous transparent margin. Design readable around 60dp. No ground plane or cast shadow outside the item, no words, letters, logos, existing game assets, watermark.
```
