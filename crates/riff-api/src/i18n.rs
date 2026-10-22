//! Translation lookup for the few labels the data layer produces itself.
//!
//! The GTK build uses gettext. The macOS client translates in Swift, so here
//! the English source string is returned unchanged.

#[cfg(feature = "gtk")]
pub use gettextrs::gettext;

#[cfg(not(feature = "gtk"))]
pub fn gettext<T: Into<String>>(msgid: T) -> String {
    msgid.into()
}
