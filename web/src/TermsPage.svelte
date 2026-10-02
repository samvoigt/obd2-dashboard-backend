<script lang="ts">
  // The terms of service (Sam, 2026-10-01): a hobby site, offered as it is, and never a safety system.
  const contact = 'sam.voigt@gmail.com'
</script>

<main class="legal">
  <h1>Terms of service</h1>
  <p class="muted">Last updated 1 October 2026</p>

  <p>
    badnewsbears.live ("the site") is run by Sam Voigt, an individual. By using it you agree to these terms. If you
    don't agree, please don't use it. Questions: <a href={`mailto:${contact}`}>{contact}</a>.
  </p>

  <h2>What the site is</h2>
  <p>
    A free, hobby dashboard for race cars: live data from a car's tablet, past sessions, laps, events and courses,
    and messages from the pit crew to the driver. It's offered as it is, and may change, pause or stop at any time.
  </p>

  <h2>Not a safety system</h2>
  <p>
    <strong>Don't rely on the site for anything to do with safety.</strong> Its data can be late, missing or wrong.
    Lap and sector times are unofficial. A crew message may not arrive, or may arrive late. Drivers should never
    use the site, or read it, in a way that takes their attention from driving. Follow the track's and the event's
    own rules, officials and flags, always.
  </p>

  <h2>Signing in and accounts</h2>
  <ul>
    <li>Only people invited by the site's master admin can sign in, with their Google account.</li>
    <li>
      Invited users can create cars, events, courses and drivers, and choose who else edits them. Only an item's
      maker, or a master admin, can delete it.
    </li>
    <li>
      You're responsible for what you create and change, and for keeping car tokens and crew passcodes private.
      Anyone with a car's token can stream as that car; anyone with its passcode can act as its crew.
    </li>
    <li>The master admin may remove any user, or anything on the site, at any time.</li>
  </ul>

  <h2>What you put on the site</h2>
  <ul>
    <li>
      <strong>Much of it is public</strong>, including a car's live position and its past sessions (see the
      <a href="/privacy">privacy policy</a>). Only stream a car, or name a driver, with the permission of the
      people involved.
    </li>
    <li>
      You keep whatever rights you have in your data. You let the site store, show and process it as needed to run
      the site.
    </li>
    <li>
      Don't put anything unlawful on the site, or anything you have no right to share. Course maps drawn from
      OpenStreetMap keep its credit, under the Open Database License.
    </li>
  </ul>

  <h2>Fair use</h2>
  <p>
    Don't try to get around signing in or the site's permissions, overload it, interfere with other cars' data, or
    pretend to be someone else.
  </p>

  <h2>No warranty, and limits on liability</h2>
  <p>
    The site is provided "as is" and "as available", with no warranties of any kind, express or implied, including
    fitness for a particular purpose. To the fullest extent the law allows, Sam Voigt isn't liable for any indirect,
    incidental or consequential damages, or for any loss of data, arising from using the site or being unable to
    use it.
  </p>

  <h2>The law that applies</h2>
  <p>These terms are governed by the laws of the Commonwealth of Massachusetts, USA.</p>

  <h2>Changes</h2>
  <p>If these terms change, the new version is posted here with a new date. Using the site afterwards means you accept it.</p>

  <p class="also">See also the <a href="/privacy">privacy policy</a>.</p>
</main>

<style>
  .legal { max-width: 760px; line-height: 1.55; }
  h2 { font-size: 1.1rem; margin: 28px 0 8px; }
  p, li { color: var(--text); }
  ul { padding-left: 20px; }
  li { margin: 6px 0; }
  .also { margin-top: 28px; }
</style>
