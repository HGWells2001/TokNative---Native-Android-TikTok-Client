export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/tiktok/exchange") {
      return new Response("Not found", { status: 404 });
    }
    try {
      const input = await request.json();
      const redirectUri = input.redirect_uri;
      if (redirectUri !== env.TIKTOK_REDIRECT_URI) {
        return json({ error: "redirect_uri mismatch" }, 400);
      }
      const form = new URLSearchParams({
        client_key: env.TIKTOK_CLIENT_KEY,
        client_secret: env.TIKTOK_CLIENT_SECRET,
        code: input.code,
        grant_type: "authorization_code",
        redirect_uri: redirectUri,
        code_verifier: input.code_verifier,
      });
      const tokenResp = await fetch("https://open.tiktokapis.com/v2/oauth/token/", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: form,
      });
      const token = await tokenResp.json();
      if (!tokenResp.ok || !token.access_token) return json(token, tokenResp.status || 400);

      const profileResp = await fetch("https://open.tiktokapis.com/v2/user/info/?fields=open_id,display_name,avatar_url", {
        headers: { Authorization: `Bearer ${token.access_token}` },
      });
      const profile = await profileResp.json();
      if (!profileResp.ok) return json(profile, profileResp.status);
      const user = profile?.data?.user || {};

      // Demo backend: does not persist refresh_token. For production, keep access/refresh tokens server-side.
      return json({
        display_name: user.display_name || "Profilo TikTok",
        avatar_url: user.avatar_url || "",
        session_id: token.open_id || "authorized",
        granted_permissions: token.scope || input.granted_permissions || "",
      });
    } catch (e) {
      return json({ error: String(e?.message || e) }, 500);
    }
  }
};

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}