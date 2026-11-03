//! The shield's filter engine: Brave's adblock-rust behind a small JNI surface for
//! `net.mangadot.scaffold.engine.AdblockNative`. One engine serves every WebView thread, and a
//! rebuilt one replaces it whole, so a check never sees a half-loaded set of lists.

use std::collections::HashSet;
use std::fs;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::sync::{Arc, RwLock};

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::Resource;
use adblock::Engine;
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jint, jstring, JNI_TRUE};
use jni::JNIEnv;

struct Engines {
    main: Engine,
    // adblock-rust has no popup type, so $popup rules live here as $document rules, checked only for new windows
    popups: Engine,
    // The privacy lists alone, which tell a blocked tracker from a blocked ad
    trackers: Engine,
}

static ENGINES: RwLock<Option<Arc<Engines>>> = RwLock::new(None);

const BLOCK: jint = 1;
const THIRD_PARTY: jint = 2;
const TRACKER: jint = 4;

const MAIN_FILE: &str = "main.dat";
const POPUP_FILE: &str = "popup.dat";
const TRACKER_FILE: &str = "tracker.dat";
const COUNT_FILE: &str = "count.txt";

fn current() -> Option<Arc<Engines>> {
    ENGINES.read().ok()?.clone()
}

fn install(engines: Engines) {
    if let Ok(mut slot) = ENGINES.write() {
        *slot = Some(Arc::new(engines));
    }
}

fn with_resources(mut engine: Engine, resources: &[Resource]) -> Engine {
    if !resources.is_empty() {
        engine.use_resources(resources.iter().cloned());
    }
    engine
}

fn read_resources(path: Option<String>) -> Vec<Resource> {
    path.and_then(|path| fs::read_to_string(path).ok())
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

// A list that isn't quite UTF-8 still has its readable rules used
fn read_list(path: &str) -> String {
    fs::read(path).map(|bytes| String::from_utf8_lossy(&bytes).into_owned()).unwrap_or_default()
}

/// Splits off a rule's options when it has any, the way list syntax marks them.
fn split_options(rule: &str) -> Option<(&str, &str)> {
    if rule.contains("##") || rule.contains("#@#") || rule.contains("#?#") {
        return None;
    }
    let cut = rule.rfind('$')?;
    Some((&rule[..cut], &rule[cut + 1..]))
}

/// A `$popup` rule rewritten as the page load it stops, or None for any other rule.
fn as_popup_rule(rule: &str) -> Option<String> {
    let (pattern, options) = split_options(rule)?;
    let mut found = false;
    let kept: Vec<&str> = options
        .split(',')
        .map(|option| {
            if option == "popup" {
                found = true;
                "document"
            } else {
                option
            }
        })
        .collect();
    found.then(|| format!("{}${}", pattern, kept.join(",")))
}

fn is_rule(line: &str) -> bool {
    !line.is_empty() && !line.starts_with('!') && !line.starts_with('[')
}

fn build_engines(lists: &[String], tracker_lists: &[String], resources: &[Resource]) -> (Engines, usize) {
    let mut main = FilterSet::new(false);
    let mut popup_rules = Vec::new();
    let mut count = 0;
    for list in lists {
        let mut kept = String::with_capacity(list.len());
        for line in list.lines() {
            let line = line.trim();
            if !is_rule(line) {
                continue;
            }
            count += 1;
            match as_popup_rule(line) {
                Some(rule) => popup_rules.push(rule),
                None => {
                    kept.push_str(line);
                    kept.push('\n');
                }
            }
        }
        main.add_filter_list(kept, ParseOptions::default());
    }
    let mut popups = FilterSet::new(false);
    popups.add_filter_list(popup_rules.join("\n"), ParseOptions::default());
    let mut trackers = FilterSet::new(false);
    for list in tracker_lists {
        trackers.add_filter_list(list.clone(), ParseOptions::default());
    }
    let engines = Engines {
        main: with_resources(Engine::new_with_filter_set(main), resources),
        popups: Engine::new_with_filter_set(popups),
        trackers: Engine::new_with_filter_set(trackers),
    };
    (engines, count)
}

fn save(engines: &Engines, count: usize, dir: &Path) -> std::io::Result<()> {
    fs::create_dir_all(dir)?;
    fs::write(dir.join(MAIN_FILE), engines.main.serialize())?;
    fs::write(dir.join(POPUP_FILE), engines.popups.serialize())?;
    fs::write(dir.join(TRACKER_FILE), engines.trackers.serialize())?;
    fs::write(dir.join(COUNT_FILE), count.to_string())
}

fn restore(dir: &Path, resources: &[Resource]) -> Option<(Engines, usize)> {
    let count = fs::read_to_string(dir.join(COUNT_FILE)).ok()?.trim().parse().ok()?;
    let mut main = Engine::default();
    main.deserialize(&fs::read(dir.join(MAIN_FILE)).ok()?).ok()?;
    let mut popups = Engine::default();
    popups.deserialize(&fs::read(dir.join(POPUP_FILE)).ok()?).ok()?;
    let mut trackers = Engine::default();
    trackers.deserialize(&fs::read(dir.join(TRACKER_FILE)).ok()?).ok()?;
    Some((Engines { main: with_resources(main, resources), popups, trackers }, count))
}

fn string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    if value.is_null() {
        return None;
    }
    env.get_string(value).ok().map(Into::into)
}

fn strings(env: &mut JNIEnv, array: &JObjectArray) -> Vec<String> {
    let length = env.get_array_length(array).unwrap_or(0);
    (0..length)
        .filter_map(|i| {
            let item = env.get_object_array_element(array, i).ok()?;
            string(env, &JString::from(item))
        })
        .collect()
}

fn new_string(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// A panic must not cross into the JVM, where it would end the app.
fn guarded<T>(fallback: T, work: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(work)).unwrap_or(fallback)
}

/// Builds from list files plus `extraRules`, and keeps a copy in `cacheDir`. Returns how many rules
/// there are. `trackerListPaths` names the lists among them that are about tracking rather than ads.
#[no_mangle]
pub extern "system" fn Java_net_mangadot_scaffold_engine_AdblockNative_build(
    mut env: JNIEnv,
    _class: JClass,
    list_paths: JObjectArray,
    tracker_list_paths: JObjectArray,
    extra_rules: JString,
    resources_path: JString,
    cache_dir: JString,
) -> jint {
    let list_paths = strings(&mut env, &list_paths);
    let tracker_list_paths = strings(&mut env, &tracker_list_paths);
    let extra_rules = string(&mut env, &extra_rules).unwrap_or_default();
    let resources_path = string(&mut env, &resources_path);
    let cache_dir = string(&mut env, &cache_dir);
    guarded(-1, || {
        let mut lists: Vec<String> = list_paths.iter().map(|path| read_list(path)).collect();
        lists.push(extra_rules);
        let tracker_lists: Vec<String> = tracker_list_paths.iter().map(|path| read_list(path)).collect();
        let resources = read_resources(resources_path);
        let (engines, count) = build_engines(&lists, &tracker_lists, &resources);
        if let Some(dir) = cache_dir {
            let _ = save(&engines, count, Path::new(&dir));
        }
        install(engines);
        count as jint
    })
}

/// Loads what [build] kept. Returns the rule count, or -1 when there's nothing usable to load.
#[no_mangle]
pub extern "system" fn Java_net_mangadot_scaffold_engine_AdblockNative_restore(
    mut env: JNIEnv,
    _class: JClass,
    cache_dir: JString,
    resources_path: JString,
) -> jint {
    let Some(dir) = string(&mut env, &cache_dir) else { return -1 };
    let resources_path = string(&mut env, &resources_path);
    guarded(-1, || match restore(Path::new(&dir), &read_resources(resources_path)) {
        Some((engines, count)) => {
            install(engines);
            count as jint
        }
        None => -1,
    })
}

/// Flags for one request: [BLOCK] when it should be stopped, [THIRD_PARTY] when it leaves the page's
/// site, and [TRACKER] when what stopped it is a tracker rather than an ad.
#[no_mangle]
pub extern "system" fn Java_net_mangadot_scaffold_engine_AdblockNative_check(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    source_url: JString,
    request_type: JString,
    popup_rules: jboolean,
) -> jint {
    let (Some(url), Some(request_type)) = (string(&mut env, &url), string(&mut env, &request_type)) else {
        return 0;
    };
    let source_url = string(&mut env, &source_url).unwrap_or_default();
    guarded(0, || {
        let Ok(request) = Request::new(&url, &source_url, &request_type, "get") else { return 0 };
        let mut flags = if request.is_third_party { THIRD_PARTY } else { 0 };
        if let Some(engines) = current() {
            let engine = if popup_rules == JNI_TRUE { &engines.popups } else { &engines.main };
            if engine.check_network_request(&request).should_block() {
                flags |= BLOCK;
                if engines.trackers.check_network_request(&request).should_block() {
                    flags |= TRACKER;
                }
            }
        }
        flags
    })
}

/// What a page at `url` needs at document start, as JSON: `css` to hide its ads, `script` with
/// its scriptlets, and `generic`, whether common ad classes and ids should be looked for too.
#[no_mangle]
pub extern "system" fn Java_net_mangadot_scaffold_engine_AdblockNative_cosmetics(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jstring {
    let Some(url) = string(&mut env, &url) else { return std::ptr::null_mut() };
    let json = guarded(String::new(), || {
        let Some(engines) = current() else { return String::new() };
        let resources = engines.main.url_cosmetic_resources(&url);
        serde_json::json!({
            "css": hiding_css(resources.hide_selectors.iter()),
            "script": resources.injected_script,
            "generic": !resources.generichide,
        })
        .to_string()
    });
    new_string(&mut env, &json)
}

/// CSS hiding whatever generic rules name among the classes and ids now on a page at `url`.
#[no_mangle]
pub extern "system" fn Java_net_mangadot_scaffold_engine_AdblockNative_genericCss(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    classes: JObjectArray,
    ids: JObjectArray,
) -> jstring {
    let Some(url) = string(&mut env, &url) else { return std::ptr::null_mut() };
    let classes = strings(&mut env, &classes);
    let ids = strings(&mut env, &ids);
    let css = guarded(String::new(), || {
        let Some(engines) = current() else { return String::new() };
        let resources = engines.main.url_cosmetic_resources(&url);
        if resources.generichide {
            return String::new();
        }
        let exceptions: HashSet<String> = resources.exceptions;
        hiding_css(engines.main.hidden_class_id_selectors(&classes, &ids, &exceptions).iter())
    });
    new_string(&mut env, &css)
}

// Each selector is its own rule, so one the browser can't parse doesn't sink the rest
fn hiding_css<'a>(selectors: impl Iterator<Item = &'a String>) -> String {
    selectors.map(|selector| format!("{selector}{{display:none!important}}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn popup_rules_become_document_rules() {
        assert_eq!(as_popup_rule("||ads.example^$popup,third-party").as_deref(), Some("||ads.example^$document,third-party"));
        assert_eq!(as_popup_rule("||ads.example^$script"), None);
        assert_eq!(as_popup_rule("example.com##.ad"), None);
    }

    #[test]
    fn blocks_and_hides() {
        let lists = vec![
            "||ads.example^\n||pop.example^$popup\nsite.example##.banner\n##.ad-box".to_string(),
        ];
        let (engines, count) = build_engines(&lists, &["||stats.example^".to_string()], &[]);
        assert_eq!(count, 4);
        let pixel = Request::new("https://stats.example/p.gif", "https://site.example/", "image", "get").unwrap();
        assert!(!engines.main.check_network_request(&pixel).should_block());
        assert!(engines.trackers.check_network_request(&pixel).should_block());
        let script = Request::new("https://ads.example/a.js", "https://site.example/", "script", "get").unwrap();
        assert!(engines.main.check_network_request(&script).should_block());
        let window = Request::new("https://pop.example/", "https://site.example/", "document", "get").unwrap();
        assert!(engines.popups.check_network_request(&window).should_block());
        assert!(!engines.main.check_network_request(&window).should_block());
        let resources = engines.main.url_cosmetic_resources("https://site.example/");
        assert!(resources.hide_selectors.contains(".banner"));
        let generic = engines.main.hidden_class_id_selectors(["ad-box"], Vec::<String>::new(), &resources.exceptions);
        assert_eq!(generic, vec![".ad-box".to_string()]);
    }

    #[test]
    fn survives_a_round_trip_through_the_cache() {
        let dir = std::env::temp_dir().join("scraffold_adblock_test");
        let (engines, count) = build_engines(&["||ads.example^".to_string()], &[], &[]);
        save(&engines, count, &dir).unwrap();
        let (restored, restored_count) = restore(&dir, &[]).unwrap();
        assert_eq!(restored_count, 1);
        let request = Request::new("https://ads.example/x.png", "https://site.example/", "image", "get").unwrap();
        assert!(restored.main.check_network_request(&request).should_block());
    }
}

#[cfg(test)]
mod real_lists {
    use super::*;
    use std::time::Instant;

    /// Run with SCRAFFOLD_LISTS set to a folder of downloaded lists and their resources.json.
    #[test]
    #[ignore]
    fn builds_from_downloaded_lists() {
        let dir = std::env::var("SCRAFFOLD_LISTS").unwrap();
        let mut lists = Vec::new();
        for entry in fs::read_dir(&dir).unwrap() {
            let path = entry.unwrap().path();
            if path.extension().is_some_and(|e| e == "txt") {
                lists.push(fs::read_to_string(path).unwrap());
            }
        }
        let resources = read_resources(Some(Path::new(&dir).join("resources.json").to_string_lossy().into_owned()));
        let started = Instant::now();
        let (engines, count) = build_engines(&lists, &[], &resources);
        println!("built {count} rules, {} resources, in {:?}", resources.len(), started.elapsed());
        let cache = std::env::temp_dir().join("scraffold_adblock_real");
        let started = Instant::now();
        save(&engines, count, &cache).unwrap();
        println!("saved in {:?}, main.dat {} bytes", started.elapsed(), fs::metadata(cache.join(MAIN_FILE)).unwrap().len());
        let started = Instant::now();
        let (engines, _) = restore(&cache, &resources).unwrap();
        println!("restored in {:?}", started.elapsed());
        let check = |url: &str, source: &str, kind: &str| {
            Request::new(url, source, kind, "get").map(|r| engines.main.check_network_request(&r).should_block()).unwrap()
        };
        println!("doubleclick script: {}", check("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://news.example/", "script"));
        println!("youtube player: {}", check("https://www.youtube.com/s/player/abc/base.js", "https://www.youtube.com/", "script"));
        println!("youtube ad ping: {}", check("https://www.youtube.com/pagead/adview?ai=x", "https://www.youtube.com/watch?v=x", "xhr"));
        let yt = engines.main.url_cosmetic_resources("https://www.youtube.com/watch?v=x");
        println!("youtube: {} selectors, script {} bytes", yt.hide_selectors.len(), yt.injected_script.len());
        let started = Instant::now();
        for _ in 0..10000 {
            check("https://cdn.example.net/assets/app.12345.js", "https://site.example/page", "script");
        }
        println!("10k checks in {:?}", started.elapsed());
    }
}
