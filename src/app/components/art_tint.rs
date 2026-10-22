//! Tinting a page with the colors of its artwork.
//!
//! The dominant color of a cover is turned into a CSS class (one per color),
//! all held by a single provider on the display.

use gtk::prelude::*;
use std::cell::RefCell;
use std::collections::BTreeMap;

const CLASS_PREFIX: &str = "art-tint-";

/// Roughly how many pixels are looked at per image.
const SAMPLES: usize = 4096;

thread_local! {
    static TINTS: RefCell<Option<(gtk::CssProvider, BTreeMap<String, String>)>> =
        const { RefCell::new(None) };
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rgb(pub u8, pub u8, pub u8);

impl Rgb {
    fn hex(self) -> String {
        format!("{:02x}{:02x}{:02x}", self.0, self.1, self.2)
    }
}

/// The color that best represents `texture`: the most common vivid color,
/// or the average one for an image without any.
pub fn dominant_color(texture: &gdk::Texture) -> Option<Rgb> {
    let mut downloader = gdk::TextureDownloader::new(texture);
    downloader.set_format(gdk::MemoryFormat::R8g8b8a8);
    let (bytes, stride) = downloader.download_bytes();
    let (width, height) = (texture.width() as usize, texture.height() as usize);
    let pixels = (0..height).flat_map(|y| {
        let row = &bytes[y * stride..];
        (0..width).map(move |x| [row[x * 4], row[x * 4 + 1], row[x * 4 + 2], row[x * 4 + 3]])
    });
    dominant_of(pixels, width * height)
}

fn dominant_of(pixels: impl Iterator<Item = [u8; 4]>, count: usize) -> Option<Rgb> {
    let step = (count / SAMPLES).max(1);
    // Per 4 bits per channel bucket: weighted sums of r, g, b and the weight
    let mut buckets: BTreeMap<u16, [f64; 4]> = BTreeMap::new();
    let mut average = [0f64; 4];
    for [r, g, b, a] in pixels.step_by(step) {
        if a < 128 {
            continue;
        }
        let (rf, gf, bf) = (r as f64, g as f64, b as f64);
        average[0] += rf;
        average[1] += gf;
        average[2] += bf;
        average[3] += 1.0;

        let max = rf.max(gf).max(bf) / 255.0;
        let min = rf.min(gf).min(bf) / 255.0;
        let saturation = if max == 0.0 { 0.0 } else { (max - min) / max };
        // Near black, near white and greys make dull tints
        if max < 0.15 || saturation < 0.15 {
            continue;
        }
        let weight = 1.0 + saturation * 3.0;
        let key = ((r as u16 >> 4) << 8) | ((g as u16 >> 4) << 4) | (b as u16 >> 4);
        let bucket = buckets.entry(key).or_default();
        bucket[0] += rf * weight;
        bucket[1] += gf * weight;
        bucket[2] += bf * weight;
        bucket[3] += weight;
    }
    let [r, g, b, w] = buckets
        .into_values()
        .max_by(|a, b| a[3].total_cmp(&b[3]))
        .unwrap_or(average);
    (w > 0.0).then(|| Rgb((r / w).round() as u8, (g / w).round() as u8, (b / w).round() as u8))
}

/// Keep the hue, but bring the color to a lightness and saturation that
/// work behind text in both color schemes.
fn soften(Rgb(r, g, b): Rgb) -> Rgb {
    let (r, g, b) = (r as f64 / 255.0, g as f64 / 255.0, b as f64 / 255.0);
    let max = r.max(g).max(b);
    let min = r.min(g).min(b);
    let l = (max + min) / 2.0;
    let d = max - min;
    let (h, s) = if d == 0.0 {
        (0.0, 0.0)
    } else {
        let s = d / (1.0 - (2.0 * l - 1.0).abs());
        let h = if max == r {
            ((g - b) / d).rem_euclid(6.0)
        } else if max == g {
            (b - r) / d + 2.0
        } else {
            (r - g) / d + 4.0
        };
        (h * 60.0, s)
    };
    let l = l.clamp(0.32, 0.5);
    let s = s.min(0.75);

    let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
    let x = c * (1.0 - ((h / 60.0) % 2.0 - 1.0).abs());
    let m = l - c / 2.0;
    let (r, g, b) = match h as u32 {
        0..=59 => (c, x, 0.0),
        60..=119 => (x, c, 0.0),
        120..=179 => (0.0, c, x),
        180..=239 => (0.0, x, c),
        240..=299 => (x, 0.0, c),
        _ => (c, 0.0, x),
    };
    let channel = |v: f64| ((v + m) * 255.0).round().clamp(0.0, 255.0) as u8;
    Rgb(channel(r), channel(g), channel(b))
}

fn tint_rule(class: &str, Rgb(r, g, b): Rgb) -> String {
    format!(
        ".{class} {{ background-image: linear-gradient(to bottom, \
         rgba({r},{g},{b},0.7) 0px, rgba({r},{g},{b},0.35) 320px, \
         rgba({r},{g},{b},0) 640px); }}"
    )
}

fn class_for(color: Rgb) -> String {
    let class = format!("{CLASS_PREFIX}{}", color.hex());
    TINTS.with(|tints| {
        let mut tints = tints.borrow_mut();
        let (provider, rules) = tints.get_or_insert_with(|| {
            let provider = gtk::CssProvider::new();
            if let Some(display) = gdk::Display::default() {
                gtk::style_context_add_provider_for_display(
                    &display,
                    &provider,
                    gtk::STYLE_PROVIDER_PRIORITY_APPLICATION,
                );
            }
            (provider, BTreeMap::new())
        });
        if !rules.contains_key(&class) {
            rules.insert(class.clone(), tint_rule(&class, color));
            let css: String = rules.values().map(String::as_str).collect();
            provider.load_from_string(&css);
        }
    });
    class
}

/// Paint the top of `widget` with the dominant color of `texture`.
pub fn tint_with_art(widget: &impl IsA<gtk::Widget>, texture: &gdk::Texture) {
    clear_tint(widget);
    if let Some(color) = dominant_color(texture) {
        widget.add_css_class(&class_for(soften(color)));
    }
}

pub fn clear_tint(widget: &impl IsA<gtk::Widget>) {
    for class in widget.css_classes() {
        if class.starts_with(CLASS_PREFIX) {
            widget.remove_css_class(&class);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prefers_vivid_colors_over_dark_backgrounds() {
        let dark = std::iter::repeat_n([10, 10, 10, 255], 900);
        let red = std::iter::repeat_n([200, 30, 30, 255], 100);
        let color = dominant_of(dark.chain(red), 1000).unwrap();
        assert_eq!(color, Rgb(200, 30, 30));
    }

    #[test]
    fn falls_back_to_the_average_of_grey_images() {
        let pixels = [[100, 100, 100, 255], [200, 200, 200, 255]];
        assert_eq!(dominant_of(pixels.iter().copied(), 2), Some(Rgb(150, 150, 150)));
        assert_eq!(dominant_of(std::iter::empty(), 0), None);
    }

    #[test]
    fn softens_to_a_readable_lightness() {
        let Rgb(r, g, b) = soften(Rgb(255, 240, 0));
        let max = r.max(g).max(b) as u32;
        let min = r.min(g).min(b) as u32;
        // Lightness at most 50%
        assert!(max + min <= 256, "{r} {g} {b}");
        // Still yellow
        assert!(r > b && g > b);
    }
}
