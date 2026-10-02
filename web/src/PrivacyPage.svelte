<script lang="ts">
  // The privacy policy (Sam, 2026-10-01): what the site collects, what's public, and who to ask. Kept in step
  // with the code: the cookies, the browser storage, the VIN rule and the outside services named here are real.
  const contact = 'sam.voigt@gmail.com'
</script>

<main class="legal">
  <h1>Privacy policy</h1>
  <p class="muted">Last updated 1 October 2026</p>

  <p>
    badnewsbears.live ("the site") shows live and past data from race cars, and is run by Sam Voigt, an
    individual. Questions, or a request to see or delete your data: <a href={`mailto:${contact}`}>{contact}</a>.
  </p>

  <h2>What the site collects</h2>
  <h3>If you sign in with Google</h3>
  <p>
    Signing in is only for people invited to edit the site. The site asks Google for your <strong>email address</strong>
    and nothing else: not your name, contacts, files or password. It keeps your email as an invited user, as the
    maker or an editor of the cars, events, courses and drivers you work on, and in its record of who changed what.
  </p>
  <h3>From a car's tablet</h3>
  <p>
    A car registered on the site streams what its tablet reads: engine and vehicle readings, speed, acceleration,
    lap and sector times, and the car's <strong>GPS position</strong>. A session's log also holds the vehicle's
    <strong>VIN</strong> as the car reports it.
  </p>
  <h3>From the pit crew and organisers</h3>
  <p>
    Messages the crew sends to a car, session names, and the events, courses and drivers people set up, including
    drivers' names and short codes.
  </p>
  <h3>From your browser</h3>
  <p>
    Two cookies, both only for signing in, and only set if you sign in: one for an invited user, and one for a
    car's crew after entering its passcode. Each lasts 30 days unless you sign out, can't be read by the page's
    scripts, and is sent only to this site. The site also remembers, in your browser's own storage, whether you
    chose metric or US units and which laps you picked to compare. There are <strong>no advertising or analytics
    cookies</strong>, and no tracking.
  </p>
  <p>
    Google Cloud, which runs the site, keeps request logs (including your IP address and browser) for up to 30 days.
  </p>

  <h2>What's public</h2>
  <p>
    The site is a public dashboard. <strong>Anyone, without signing in, can see</strong>: each car's name and live
    state; its live dashboard while it's streaming, including where it is on the map; the crew's messages to it;
    its past sessions, with their routes, readings, laps and messages; events and their results; drivers' names
    and codes; and courses.
  </p>
  <p>
    <strong>Never public:</strong> email addresses, car tokens and crew passcodes, and the VIN. The VIN is only in a session's raw download, which only the car's maker, the editors
    they choose, and the site's master admins can get.
  </p>

  <h2>Who else is involved</h2>
  <ul>
    <li><strong>Google Cloud</strong> hosts the site and stores its data, in the United States.</li>
    <li><strong>Google Sign-In</strong> confirms who you are when you press Sign in. Google's script loads only then.</li>
    <li>
      <strong>OpenStreetMap</strong> and the <strong>U.S. Geological Survey</strong> supply map images. Your browser
      fetches them directly, so those services see your IP address, as with any map on the web.
    </li>
  </ul>
  <p>The site doesn't sell or share your data with anyone else, and shows no ads.</p>

  <h2>How long data is kept, and your choices</h2>
  <p>
    Sessions, courses, events and drivers are kept until the person who made them, or a master admin, deletes
    them. An invited user can be removed at any time, which ends their sign-in at once. To have your data, or a
    car's, deleted, or to see what's held about you, email <a href={`mailto:${contact}`}>{contact}</a>.
  </p>

  <h2>Children</h2>
  <p>The site isn't meant for children under 13, and doesn't knowingly collect their information.</p>

  <h2>Changes</h2>
  <p>If this policy changes, the new version is posted here with a new date.</p>

  <p class="also">See also the <a href="/terms">terms of service</a>.</p>
</main>

<style>
  .legal { max-width: 760px; line-height: 1.55; }
  h2 { font-size: 1.1rem; margin: 28px 0 8px; }
  h3 { font-size: 1rem; margin: 16px 0 4px; }
  p, li { color: var(--text); }
  ul { padding-left: 20px; }
  li { margin: 4px 0; }
  .also { margin-top: 28px; }
</style>
