# Configuration based espanso compatible app for android. X-platform expansions
  <p align="center">
    <a href="https://github.com/lochidev/TextComparePro/issues">Report Bug</a>
    ·
    <a href="https://github.com/lochidev/TextComparePro/issues">Request Feature</a>
    ·
    <a href="https://github.com/lochidev/TextComparePro/releases">Releases</a>
    ·
    <a href="https://github.com/lochidev/TextComparePro/blob/master/examples/config.yml">Example config</a>
    ·
    <a href="https://espanso.org/docs/get-started/">Espanso docs</a>
  </p>

Send custom messages with a trigger. Want to quickly type out the current date in a specific format? Or do you want your emojis to replace your triggers? You can do it all cross platform with espanso but now on android too with this app!

<p align="center">
    <a href="https://apt.izzysoft.de/fdroid/index/apk/com.dingleinc.texttoolspro"><img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height="80"></a>
    <a href="https://play.google.com/store/apps/details?id=com.dingleinc.texttoolspro"><img src="https://cdn.rawgit.com/steverichey/google-play-badge-svg/master/img/en_get.svg" height="80"></a>
    <a href="https://github.com/lochidev/TextComparePro/releases/latest"><img src="https://raw.githubusercontent.com/andOTP/andOTP/master/assets/badges/get-it-on-github.png" height="80"></a>
  </p>

# Announcements
Update: 7/18/2025

I've decided that I will only update the app once every 6 months. This is due to increased workload from my studies. I believe this app is now feature complete and has achieved it's original goals. What we have achieved so far,
- Free and open source forever.
- Provides unlimited unrestricted expansions.
- Supports most of essential espanso features including forms.
- Uses no internet permissions.
- Provides a way to keep your config files in sync with desktop espanso program. See <a href="https://github.com/lochidev/Expandroid/issues/55">#55</a> 

The app will surely receive updates for all major android releases. Thank you for using the app and all the stars :D

# Notes 
Espanso configuration YML files will take a few tries to parse correctly. Try removing some matches, and make sure it's compliant with the YML specs. Some working examples can be found <a href="https://github.com/lochidev/TextComparePro/blob/master/examples/config.yml">here</a>. Please also note that only the following extensions are supported -> date, clipboard, random and echo. Finally, note that not all espanso/rust chrono date time formats are supported. Supported formats are,
- %Y, %m, %b, %B, %h, %d, %e, %a, %A, %j, %w, %u, %D, %F, %H, %I, %p, %M, %S, %R, %T, %r

You can further customize date time formats by referring to the C# DateTime.ToString() method documentation from Microsoft.

Clipboard extension will not work on android 10 or higher due to security measures introduced by google. However a workaround was found at issue: <a href="https://github.com/lochidev/Expandroid/issues/35#issue-2531110035">#35</a> and should work on all devices after v7.1.0

Note: I have not tested this app on android versions below 12.

Starting with v7.0.0 forms support has been added.
Multi-line, choice & list have been added.
Note that everything under "Using Forms with Script and Shell extensions" is not supported in the document: https://espanso.org/docs/matches/forms/
# Build

CLI build instructions -> https://learn.microsoft.com/en-us/dotnet/maui/android/deployment/publish-cli

# Consider donating for work already done if you found this app useful! 💙
<a href="https://www.buymeacoffee.com/lochi" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/default-yellow.png" alt="Buy Me A Coffee" height="41" width="174"></a>

BTC - bc1q0tv7u3yngq3xpmwlu4gzv8rnez27pv3xcsk6t8

LTC - ltc1qjphn23kql69c0ul6qu88sdsf09qxceswt78yey

