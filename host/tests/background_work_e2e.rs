//! Work an agent goes on with after its turn ended (like Hermes' background subagents), through a real host and a
//! fixture agent: a permission request it makes then gets the answer the bot's permission setting gives without a
//! card, and only for the bot's own session.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

mod common;

use common::host::Host;
use serde_json::{Value, json};
use std::time::{Duration, Instant};

/// The host's answer to the permission request the agent makes after answering `text`, the `nth` it got.
async fn answer_after(host: &Host, bot: &str, text: &str, nth: usize) -> Value {
    host.reply(bot, text).await;
    let file = host.home.join("bots").join(bot).join("workspace").join("responses.jsonl");
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        if let Some(line) = std::fs::read_to_string(&file).ok().and_then(|log| log.lines().nth(nth).map(str::to_owned))
        {
            return serde_json::from_str(&line).unwrap();
        }
        assert!(Instant::now() < deadline, "the host never answered the permission request");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

#[tokio::test]
async fn a_permission_asked_after_the_turn_gets_the_bots_automatic_answer() {
    let host = Host::start().await;
    let bot = host.fixture_bot("Hermes", "late_permission_agent.py", "").await;

    // A bot that asks first: no card waits for it, so it's declined (the way ACP says it), not refused as unsupported.
    let asked = answer_after(&host, &bot, "write the notes", 0).await;
    assert_eq!(asked["result"]["outcome"]["outcome"], "cancelled", "{asked}");

    host.call("updateBot", json!({"id": bot, "permission": "auto"})).await;
    let approved = answer_after(&host, &bot, "write the notes", 1).await;
    assert_eq!(approved["result"]["outcome"], json!({"outcome": "selected", "optionId": "yes"}), "{approved}");

    // Never for a session the host didn't open for this bot.
    let elsewhere = answer_after(&host, &bot, "write the notes elsewhere", 2).await;
    assert_eq!(elsewhere["result"]["outcome"]["outcome"], "cancelled", "{elsewhere}");
}
