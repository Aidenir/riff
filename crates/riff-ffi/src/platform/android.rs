use std::sync::Once;

use jni::objects::{JClass, JObject};
use jni::JNIEnv;

use crate::CoreConfig;

pub fn init_logging() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("riff")
            .with_filter(
                android_logger::FilterBuilder::new()
                    .parse("riff=info,riff_ffi=debug,riff_player=info,librespot=warn")
                    .build(),
            ),
    );
    log_panics();
}

/// Panics on core threads otherwise vanish without a trace in logcat.
fn log_panics() {
    let default = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        error!("panic: {info}");
        default(info);
    }));
}

/// The app always passes its own folders on Android; this is only used by
/// callers that ask for a default anyway.
pub fn default_config() -> CoreConfig {
    CoreConfig {
        support_dir: "/data/local/tmp/riff".into(),
        cache_dir: "/data/local/tmp/riff/cache".into(),
    }
}

/// Called once from `NativeLib.init(context)` before the core is created.
/// cpal reaches the Android audio APIs through `ndk_context`, which needs the
/// Java VM and an application context.
#[no_mangle]
pub extern "system" fn Java_com_aidenir_riff_core_NativeLib_init(
    env: JNIEnv,
    _class: JClass,
    context: JObject,
) {
    static INIT: Once = Once::new();
    INIT.call_once(|| init_context(env, context));
}

fn init_context(env: JNIEnv, context: JObject) {
    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            error!("no Java VM: {e}");
            return;
        }
    };
    let context = match env.new_global_ref(context) {
        Ok(context) => context,
        Err(e) => {
            error!("no global reference to the context: {e}");
            return;
        }
    };
    unsafe {
        ndk_context::initialize_android_context(
            vm.get_java_vm_pointer().cast(),
            context.as_obj().as_raw().cast(),
        );
    }
    // The context must outlive every audio stream, so it is never released.
    std::mem::forget(context);
}
