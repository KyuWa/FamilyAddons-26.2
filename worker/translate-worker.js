// FamilyAddons translator — the AI backend, running on Groq.
//
// Cloudflare Worker, same setup as the update proxy. The mod posts each chat
// line here; a Llama model translates it knowing it is reading Hypixel
// SkyBlock chat, which is what makes slang, abbreviations and game jargon come
// out right without a dictionary.
//
// Setup (dashboard, no tools needed):
//   1. Groq: console.groq.com -> API Keys -> Create -> copy the key.
//   2. Cloudflare: Workers & Pages -> Create -> Start with Hello World ->
//      name it exactly  fa-translate  -> Deploy.
//   3. Edit code -> replace everything with this file -> Deploy.
//   4. Settings -> Variables and Secrets -> add two SECRETS:
//        GROQ_API_KEY = the Groq key
//        FA_SECRET    = 41d050ef-7801-47bd-880e-f0e052a3bbc3   (KeyFetcher.SECRET_TOKEN)
//
// The mod's default endpoint is https://fa-translate.220395610.workers.dev,
// so with that worker name nobody has to configure anything. If the worker is
// missing or errors, the mod falls back to Google on its own.
//
// Request:  POST { "text": "alguien me carrea t5 xfa", "target": "en" }
//           header X-FA-Secret: <FA_SECRET>
// Response: 200 { "translation": "can someone carry me in t5 please", "detected": "es" }

// Groq retired the Llama 3.x models for free/developer accounts on
// 2026-06-17 (console.groq.com/docs/deprecations); these are the recommended
// replacements. Try the bigger model first; on a 429 fall through to the
// smaller one so translation keeps working after the daily cap. Only if both
// refuse does the mod see a 429.
const MODELS = ["openai/gpt-oss-120b", "openai/gpt-oss-20b"];
const MAX_CHARS = 300;

const SYSTEM_PROMPT = `You translate Hypixel SkyBlock (Minecraft) in-game chat.
Rules:
- Translate the message into the requested target language.
- This is casual gamer chat full of slang, abbreviations, typos and missing accents. Translate the MEANING, not the words. Keep the tone (casual, joking, rude) the same.
- Keep game terms, item names, player names, numbers and abbreviations exactly as written: Kuudra, t5, f7, m7, carry, gg, ez, afk, hub, party, guild, bazaar, ah, coins, 10m, mage, berserk, archer, tank, healer, slayer, dungeon, etc. Placeholders like TRM0, TRM1 must be copied through unchanged.
- Spanish/Portuguese gamer slang: "manco" = bad player, "crack"/"capo" = very good player, "carrear"/"llevar" in this context = carry, "chetado" = overpowered, "rushear" = rush.
- Chat shorthand: q/k=que, xq/pq=porque, xfa/pf=por favor, tmb/tb=también, ns=no sé, u=you, ur=your, wanna=want to, nty=no thanks, nvm=never mind, etc.
- Spanish profanity shorthand, translate with the same rudeness: ctm = "chinga tu madre" (fuck / damn it), ptm = "puta madre" (damn it), nmms/nmm = "no mames" (no way / you're kidding), alv = "a la verga" (screw it), vrg = "verga", pndjo = "pendejo", kbron = "cabrón". "me cagué" in chat means "I freaked out" or "I screwed up", never literal.
- Keyboard mashing (asdfgh, NDHUJASHJDSA) and laughter (jajaja, jsjs, kkk) stay as they are.
- Typos are constant: swapped or missing letters (pro = por, qeu = que, tabien = también, haber = a ver, ola = hola, kiero = quiero). Read the intended word from context; never translate a typo literally. The same goes for other scripts — Arabic, Russian, Turkish and Portuguese players make the same kind of typos.
- Arabic gamer slang: "ضف وجهك" / "ضف وشك" = انقلع = get lost / piss off (NOT literal); انقلع = get lost; يلا = come on / let's go; خلاص = enough / done; طز = whatever, don't care; والله = I swear / seriously; حرام = that's unfair.
- When a short message is genuinely ambiguous, pick the most likely casual-chat meaning and commit to it; do not hedge.
- If the message is already in the target language, return it unchanged.
- Respond with JSON only, no markdown: {"detected": "<ISO 639-1 code of the source language>", "translation": "<translation>"}. When the request asks for notes, add "notes" as described there.`;

// Shift-click in the mod: same translation, but the model also explains the
// slang it found, and gets time to think about it.
const DEEP_INSTRUCTION =
  `Also add "notes": a short breakdown, written in the target language, of every slang word, abbreviation, insult, typo or idiom in the message — format: term = meaning; term = meaning. Keep each meaning under ten words. Write every term in Latin letters: transliterate Arabic, Cyrillic, Greek, CJK and other scripts (e.g. shuf wajhak = show your face), because the game cannot display mixed right-to-left text. Never use double quotes inside notes (the JSON must stay valid); use single quotes if you must quote. Empty string if there is nothing worth explaining.`;

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return json({ error: "POST only" }, 405);
    }
    if (env.FA_SECRET && request.headers.get("X-FA-Secret") !== env.FA_SECRET) {
      return json({ error: "forbidden" }, 403);
    }
    if (!env.GROQ_API_KEY) {
      return json({ error: "GROQ_API_KEY secret not set" }, 500);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "bad json" }, 400);
    }

    const text = String(body.text ?? "").trim().slice(0, MAX_CHARS);
    const target = String(body.target ?? "en").trim().slice(0, 8);
    const deep = body.mode === "deep";
    if (!text) return json({ error: "empty text" }, 400);

    const userMessage =
      `Target language code: ${target}\n\nMessage: ${text}` +
      (deep ? `\n\n${DEEP_INSTRUCTION}` : "");

    const callGroq = (model, jsonMode) =>
      fetch("https://api.groq.com/openai/v1/chat/completions", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${env.GROQ_API_KEY}`,
        },
        body: JSON.stringify({
          model,
          // Deterministic: the same line should translate the same way twice.
          temperature: 0,
          // Reasoning tokens count against this budget, so deep mode needs
          // real headroom or the answer never arrives.
          max_tokens: deep ? 2048 : 256,
          // gpt-oss are reasoning models. A quick chat line needs none of
          // that; a shift-click deep translate is allowed to think.
          reasoning_effort: deep ? "medium" : "low",
          ...(jsonMode ? { response_format: { type: "json_object" } } : {}),
          messages: [
            { role: "system", content: SYSTEM_PROMPT },
            { role: "user", content: userMessage },
          ],
        }),
      });

    let lastStatus = 502;
    const upstream = []; // what Groq said per model, surfaced on total failure
    for (const model of MODELS) {
      let res = await callGroq(model, true);

      // Strict JSON mode rejects the whole reply over one stray quote in the
      // notes. Retry once without it and parse leniently below.
      if (res.status === 400) {
        const err = await res.json().catch(() => ({}));
        const message = err?.error?.message ?? "";
        if (/json/i.test(message)) {
          res = await callGroq(model, false);
        } else {
          lastStatus = 502;
          upstream.push({ model, status: 400, message });
          continue;
        }
      }

      if (res.status === 429) {
        lastStatus = 429; // rate limited on this model — try the next one
        upstream.push({ model, status: 429 });
        continue;
      }
      if (!res.ok) {
        lastStatus = 502;
        let message = "";
        try {
          const err = await res.json();
          message = err?.error?.message ?? JSON.stringify(err).slice(0, 200);
        } catch {
          message = `HTTP ${res.status}`;
        }
        upstream.push({ model, status: res.status, message });
        continue;
      }

      const data = await res.json();
      const raw = (data.choices?.[0]?.message?.content ?? "").trim();
      const parsed = parseLenient(raw);
      const translation = parsed?.translation ?? raw;
      if (!translation) continue;

      return json({
        translation,
        detected: parsed?.detected ?? "",
        notes: parsed?.notes ?? "",
        model,
      });
    }

    return json({ error: lastStatus === 429 ? "rate limited" : "upstream error", upstream }, lastStatus);
  },
};

// Pulls {"detected","translation","notes"} out of a reply that may be wrapped
// in prose or code fences, or have a stray unescaped quote in notes.
function parseLenient(raw) {
  const start = raw.indexOf("{");
  const end = raw.lastIndexOf("}");
  if (start < 0 || end <= start) return null;
  const slice = raw.slice(start, end + 1);
  try {
    return pick(JSON.parse(slice));
  } catch {
    /* fall through to field-by-field extraction */
  }
  const field = (name) => {
    // "name": "…" — value runs to the last quote before the next key or the end.
    const m = slice.match(new RegExp(`"${name}"\\s*:\\s*"([\\s\\S]*?)"\\s*(?:,\\s*"\\w+"\\s*:|\\})`));
    return m ? m[1].replace(/\\"/g, '"') : undefined;
  };
  const out = { translation: field("translation"), detected: field("detected"), notes: field("notes") };
  return out.translation ? out : null;
}

function pick(obj) {
  return {
    translation: typeof obj.translation === "string" ? obj.translation : undefined,
    detected: typeof obj.detected === "string" ? obj.detected : "",
    notes: typeof obj.notes === "string" ? obj.notes : "",
  };
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json" },
  });
}
