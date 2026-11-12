use crate::env_util::{resolve_prefix, rewrite_prefix_paths, sync_runtime_bridges};
use std::path::PathBuf;

pub fn run(args: &[String]) -> i32 {
    let prefix = resolve_prefix();
    let targets: Vec<PathBuf> = if args.is_empty() {
        vec![prefix.clone()]
    } else {
        args.iter().map(PathBuf::from).collect()
    };

    let count = rewrite_prefix_paths(&targets, &prefix);
    sync_runtime_bridges(&prefix);
    println!("agy-fix-paths: rewrote {} file(s) -> {}", count, prefix.display());
    0
}
