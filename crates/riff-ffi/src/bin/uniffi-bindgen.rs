fn main() {
    #[cfg(target_os = "macos")]
    uniffi::uniffi_bindgen_main()
}
