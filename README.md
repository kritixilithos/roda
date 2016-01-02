# Röda

Röda on uusi ohjelmointikieleni! Se perustuu syntaksiltaan ja ominaisuuksiltaan sh-skripteihin.

## Käyttäminen

Röda vaati toimiakseen [Nept-kirjaston](https://github.com/fergusq/nept).

Neptin ja Rödan kääntäminen Gradlella:

```sh
$ git clone --recursive https://github.com/fergusq/roda.git
$ cd roda
roda $ gradle build
```

Rödan mukana tulee interaktiivinen tulkki, jota voi käyttää valitsimella `-i`.

## Perustietoja

Röda-funktiolla on parametrit, sekä sisään- ja ulostulo, jotka ovat jonoja. Funktio ei varsinaisesti
"palauta" mitään arvoa, vaan funktion paluuarvo voidaan ajatella listana kaikista sen ulostulovirran
arvoista. Funktio voi lukea sisääntuloaan `pull`-komennolla ja työntää ulostuloon arvoja `push`-komennolla.

`main`-funktion sisään- ja ulostulo on kytketty standardisyötteeseen ja -tulosteeseen.

Alla on yksinkertainen duplicate-funktio, joka lukee yhden arvon ja palauttaa kaksi arvoa. Koska sitä
kutsutaan `main`-funktiosta, lukee se syötettä suoraan standardisyötteestä.
```
duplicate {
	pull value
	push value
	push value
}

main {
	push "Syötä tekstiä: "
	duplicate
}
```

Funktioita voi putkittaa `|`-operaattorilla, jolloin niiden sisään- ja ulostulovirrat kytketään toisiinsa.
Seuraava ohjelma tulostaa kahtena tiedoston "tieto.txt" ensimmäisen rivin:

```
cat "tieto.txt" | duplicate
```

Jos kaikki rivit haluttaisiin kahdentaa, olisi tehtävä uusi versio duplicate-funktiosta, joka lukee
kaiken mahdollisen syötteen:

```
duplicate {
	while pull -r value; do
		push value
		push value
	done
}
```

`pull -r` (**r**esponse) palauttaa `true`n tai `false`n riippuen siitä, onko luettavaa vielä jäljellä.

## Perussyntaksi

### Rivinvaihdot

Joissakin kohdissa on oltava rivinvaihto.

- Lauseet erotellaan toisistaan rivinvaihdoilla.
- `for`, `while` ja `if` vaativat rivinvaihdon ennen koodilohkoa.
- Nimettömän funktion parametrilistan jälkeen on oltava rivinvaihto.

Rivinvaihtojen tilalla voi käyttää `;`-merkkiä ja toisinpäin.

### Kommentit

Röda käyttää C-tyylisiä `/* ... */`-kommentteja.

### Funktiot

Röda-ohjelma on joukko funktioita:
```
funktio1 parametrit {

}

funktio2 parametrit {

}
```

Funktiolla voi ottaa vaihtelevan määrän argumentteja, jolloin viimeiselle parametrille pitää antaa
`...`-määrite:

```
duplicate_files files... {
	for file in files; do
		cat file | duplicate
	done
}
```

Funktio voi ottaa myös muuttujaviittauksen, jolloin ko. parametrille pitää antaa `&`-määrite. Seuraava funktio
lukee kaksi arvoa, tekee niistä listan ja asettaa sen muuttujaan.

```
pull_twice &variable {
	pull value1
	pull value2
	variable = (value1 value2)
}
```

### Lauseet

Funktioiden sisällä on lauseita, jotka koostuvat yhdestä tai useammasta putkitetusta komennosta.
Putki yhdistää komentojen ulos- ja sisääntulot toisiinsa. Lauseen ensimmäisen ja viimeisen komennon
sisään- ja ulostulo on kytketty isäntäfunktion sisään- ja ulostuloon.

```
komento1 argumentit | komento2 argumentit | komento3 argumentit
```

### Komennot

Komento voi olla joko funktiokutsu, muuttujakomento tai ohjausrakenne.

#### Funktiokutsu

Funktiokutsu koostuu funktiosta ja argumenteista, joita ei ole erikseen eroteltu toisistaan.
Myös muitakin arvoja kuin funktioita voidaan kutsua ikään, kuin ne olisivat funktioita. Nämä
erityistapaukset on alempana.

Funktion argumentteina mahdollisesti olevat funktiokutsut on kytketty isäntäfunktion virtaan, eikä
putkeen. Vain kutsuttava funktio putkittuu.

#### Eri arvojen "kutsuminen"

##### Listat

Listan "kutsuminen" työntää kaikki listan alkiot ulostulovirtaan:
```
("rivi1\n" "rivi2\n" "rivi3\n") | write tiedosto
```

#### Muuttujat

Uuden muuttujan voi luoda operaattorilla **`:=`**:
```
tiedosto := "tieto.txt"
ikä := 73
tytöt := ("Annamari" "Reetta" "Vilma")
```

Muuttujalle voi asettaa uuden arvon operaattorilla **`=`**:
```
ikä = 74
tytöt[1] = "Liisa"
```

Listaan voi lisätä arvon operaattorilla **`+=`**:
```
tytöt += "Maija"
```

Merkkijonon perään voi lisätä tekstiä operaattorilla **`.=`**:
```
nimi := etunimi
nimi .= " "..sukunimi
```

Lukua voi kasvattaa tai vähentää operaattoreilla **`++`** ja **`--`**:
```
ikä ++
voimat --
```

Muuttujan voi tuhota käyttämällä komentoa `undefine`.
Normaalisti muuttujia ei kuitenkaan tarvitse tuhota erikseen.
```
undefine nimi
```
Muuttujan tuhoaminen ei poista muuttujaa varmasti, sillä se saattaa olla määritelty jollakin toisellakin ohjelman
tasolla.
```
nimi := "Lissu"
{
	nimi := "Emilia"
	push nimi "\n" /* tulostaa Emilian */
	undefine nimi
	push nimi "\n" /* tulostaa Lissun */
}
push nimi /* tulostaa Lissun */
```
Muuttujan voi tuhota kokonaan käyttäen silmukkaa ja `?`-operaattoria, joka kertoo, onko muuttuja olemassa.
```
while nimi?; do
	undefine nimi
done
```

Seuraavaksi vielä kaikki muuttujaoperaattorit taulukossa:

| Operaattori | Esimerkki          | Selitys                                            |
|:-----------:| ------------------ | -------------------------------------------------- |
| `:=`        | `nimi := "Liisa"`  | Luo uuden muuttujan nykyiseen muuttujaympäristöön. |
| `=`         | `nimi = "Maija"`   | Ylikirjoittaa aiemmin luodun muuttujan arvon.      |
| `?`         | `nimi?`            | Työntää ulostulovirtaan totuusarvon `true` tai `false` riippuen siitä, onko muuttuja olemassa |
| `+=`        | `tytöt += "Nea"`   | Lisää listaan elementin.                           |
| `.=`        | `tytöt .= ("Annabella" "Linn")` | Yhdistää listaan toisen listan.       |
| `.=`        | `nimi .= sukunimi` | Lisää tekstin merkkijonon loppuun.                 |
| `~=`        | `nimi ~= "ae" "ä"` | Tekee annetut korvaukset merkkijonoon, toimii kuten funktio `replace`. |
| `+=`, `-=`, `*=`, `/=` | `pisteet *= 2` | Suorittaa laskutoimituksen lukumuuttujalla. |
| `++`, `--`  | `varallisuus --`   | Kasvattaa tai vähentää lukumuuttujan arvoa.        |

#### Ohjausrakenteet

Ohjausrakenteita ovat `if`, `while`, `for` ja `try`.

**`if`** ja **`while`** suorittavat annetun lauseen ja olettavat sen palauttavan joko arvon `true` tai arvon `false`.
Muut arvot tulkitaan aina samoin kuin `true`. Vain yksi arvo luetaan.

Sisäänrakennetuista funktioista vain `true`, `false`, `test`, `random` (ks. alempana)
ja `pull -r` palauttavat totuusarvon.

```
if test ikä -lt 18; do
	push "Olet liian nuori!\n"
done
```

```
while test vastaus -not-matches "kyllä|ei"; do
	push "Vastaa kyllä tai ei: "
	pull vastaus
done
```

**`for`** käy läpi annetun listan kaikki arvot:

```
tytöt := (("Annamari" 1996) ("Reetta" 1992) ("Vilma" 1999))
for tyttö in tytöt; do
	push "Hänen nimensä on "..tyttö[0].." ja hän on syntynyt vuonna "..tyttö[1].."\n"
done
```

**`try`** suorittaa annetun komennon tai lohkon ja ohittaa hiljaisesti kaikki vastaan tulleet virheet.
Aion ehkä tehdä jonkinlaisen virheenkäsittelytoiminnon siihen tulevaisuudessa. Tällä hetkellä tätä
kannattaa käyttää vain palvelimissa ym. joissa prosessin on pysyttävä virheistä huolimatta päällä.

```
while true; do
	try do
		hae viestit
		käsittele viestit
	done
done
```

### Lausekkeet

Funktion argumentit (ja kutsuttava funktio itsekin) ovat lausekkeita. Lauseke voi olla joko muuttuja,
luku, merkkijono, lippu, lista, komento tai nimetön funktio. Lisäksi Rödassa on muutama operaattori
merkkijonojen ja listojen käsittelemiseen.

#### Luvut, merkkijonot ja liput

Kaikki funktiot hyväksyvät lukujen tilalla merkkijonoja (joiden toki pitää sisältää vain lukuja)
ja merkkijonojen tilalla lukuja. Optimointisyistä on kuitenkin aina hyvä käyttää lukuliteraaleja kaikkialla,
missä mahdollista.

Viiva-merkki (`-`) aloittaa lippuliteraalin. Liput ovat syntaksisokeria merkkijonoille. `-lippu` ja `"-lippu"`
ovat sama asia.

Merkkijonoja voi yhdistellä `..`-operaattorilla ja niiden pituuden voi saada `#`-operaattorilla.

```
nimi := etunimi.." "..sukunimi
push "Nimesi pituus on " #nimi "\n"
```

#### Listat

Listaliteraali on joukko sulkujen ympäröimiä arvoja, joita ei ole mitenkään erikseen eroteltu toisistaan.

Listasta voi hakea yksittäisiä alkioita `[]`-operaattorilla. Lisäksi osalistoja voi luota `[:]`-operaattorilla.
Kuten merkkijonoillakin, `#` palauttaa listan koon.

Kaikki listan alkiot voi yhdistää merkkijonoksi `&`-operaattorilla, jonka toinen operandi on merkkijono, joka
pistetään alkioiden väleihin.

```
tytöt := ("Annamari" "Reetta" "Vilma" "Susanna")
push "Tyttöjä on " #tytöt " kpl.\n"
push "Ensimmäinen tyttö on " tytöt[0] " ja viimeinen " tytöt[-1] ". "
push "Välissä ovat " tytöt[1:-1]&" ja " ".\n"
```

Jos listaan yhdistää merkkijonon, yhdistetään se kaikkiin listan alkioihin:

```
sukunimi := "Kivinen"
sisarukset := ("Maija" "Ilmari")
kokonimet := sisarukset.." "..sukunimi
push "Sisarusten koko nimet ovat " kokonimet&" ja " ".\n"
```

Operaattorit taulukossa:

| Operaattori | Selitys               | Ottaa                                   | Palauttaa              |
|:-----------:| --------------------- | --------------------------------------- | ---------------------- |
| `..`        | Yhdistää merkkijonoja | 2 merkkijonoa tai listan ja merkkijonon | Merkkijonon tai listan |
| `&`         | Yhdistää listan alkiot merkkijonoksi | Listan ja merkkijonon    | Merkkijonon            |
| `#`         | Palauttaa arvon pituuden | Listan tai merkkijonon               | Kokonaisluvun          |
| `[]`        | Palauttaa listan alkion  | Listan ja kokonaisluvun              | Alkion                 |
| `[:]`       | Palauttaa listan osalistan | Listan ja nollasta kahteen kokonaisluku | Listan            |

Laskujärjestys:

| Sija | Operaattorit |
|:----:| ------------ |
| 1.   | `[]`, `[:]`  |
| 2.   | `#`          |
| 3.   | `&`          |
| 4.   | `..`         |

#### Komento

Lauseke voi olla myös komento tai putkitettuja komentoja. Tälloin lausekkeen arvoksi tulee lista,
joka on muodostettu kaikista viimeisen komennon ulostulon antamista arvoista.

Seuraava ohjelma tulostaa tiedoston rivinumeroiden kera.

```
rivit := !(cat tiedosto)
i := 1
for rivi in rivit; do
	push i " " rivi "\n"
	i ++
done
```

#### Nimetön funktio

Nimetön funktio toimii kuten tavallinenkin funktio. Syntaksi on `{ |parametrit|; koodi }`.

Seuraavassa koodissa määritellään `filter`-funktio, joka lukee arvoja ja palauttaa osan niistä.

```
filter cond_function {
	while pull -r value; do
		if cond_function value; do
			push value
		done
	done
}
```

Funktiota käytetään antamalla sille nimetön funktio (tai tavallinenkin funktio käy), joka palauttaa
`true`n tai `false`n.

```
tytöt := (("Annamari" 1996) ("Reetta" 1992) ("Vilma" 1999))
tytöt | filter { |tyttö|; test tyttö[1] -gt 1995 } | while pull -r tyttö; do
	push tyttö[0] " on vielä nuori.\n"
done
```

#### Aritmetiikkatila

Koska Rödan muu syntaksi varaa jo sulut `( )` ja miinusmerkin `-`, ei niitä voi käyttää laskutoimituksiin.
Tämän rajoituksen kiertämiseksi Rödassa on aritmetiikkatila, jossa tavallinen syntaksi ei enää päde.
Tilaan pääsee heittomerkillä `'`.
```
p := 'i/2+7'
k := '(p-10)*2'
```

Aritmetiikkatilassa voi käyttää tavallisia sulkeita. Lisäksi seuraavat operaattorit ovat käytössä:

| Operaattori | Selitys                     | Ottaa             | Palauttaa     |
|:-----------:| --------------------------- | ----------------- | ------------- |
| `&&`        | Looginen JA                 | 2 totuusarvoa     | Totuusarvon   |
| `||`        | Looginen TAI                | 2 totuusarvoa     | Totuusarvon   |
| `^^`        | Looginen JOKO-TAI           | 2 totuusarvoa     | Totuusarvon   |
| `=`         | Yhtäsuuruus                 | Mitä tahansa      | Totuusarvon   |
| `!=`        | Erisuuruus                  | Mitä tahansa      | Totuusarvon   |
| `<`         | Pienempi kuin               | 2 kokonaislukua   | Totuusarvon   |
| `>`         | Suurempi kuin               | 2 kokonaislukua   | Totuusarvon   |
| `<=`        | Pienempi tai yhtäsuuri kuin | 2 kokonaislukua   | Totuusarvon   |
| `>=`        | Suurempi tai yhtäsuuri kuin | 2 kokonaislukua   | Totuusarvon   |
| `&`         | Bittitason JA               | 2 kokonaislukua   | Kokonaisluvun |
| `|`         | Bittitason TAI              | 2 kokonaislukua   | Kokonaisluvun |
| `^`         | Bittitason JOKO-TAI         | 2 kokonaislukua   | Kokonaisluvun |
| `<<`        | Bittitason vasen siirto     | 2 kokonaislukua   | Kokonaisluvun |
| `>>`        | Bittitason oikea siirto     | 2 kokonaislukua   | Kokonaisluvun |
| `>>>`       | Bittitason etumerkitön oikea siirto | 2 kokonaislukua | Kokonaisluvun |
| `+`         | Yhteenlasku                 | 2 kokonaislukua   | Kokonaisluvun |
| `-`         | Vähennyslasku               | 2 kokonaislukua   | Kokonaisluvun |
| `*`         | Kertolasku                  | 2 kokonaislukua   | Kokonaisluvun |
| `/`         | Jakolasku                   | 2 kokonaislukua   | Kokonaisluvun |
| Unäärinen `-` | Vastaluku                 | Kokonaisluvun     | Kokonaisluvun |
| Unäärinen `~` | Bittitason EI             | Kokonaisluvun     | Kokonaisluvun |
| Unäärinen `!` | Looginen EI               | Totuusarvon       | Totuusarvon   |

Laskujärjestys:

| Sija | Operaattorit                                               |
|:----:| ---------------------------------------------------------- |
| 1.   | `[]`, `[:]`                                                |
| 2.   | Unäärinen `-`, unäärinen `~`, unäärinen `!`, unäärinen `#` |
| 3.   | `*`, `/`                                                   |
| 4.   | `+`, binäärinen `-`                                        |
| 5.   | `&`, `|`, `^`, `<<`, `>>`, `>>>`                           |
| 6.   | `<`, `>`, `<=`, `>=`                                       |
| 7.   | `=`, `!=`                                                  |
| 8.   | `&&`, `||`, `^^`                                           |

## Lista sisäänrakennetuista funktioista

Tässä listassa sulkuja `()` käytetään ryhmittelemiseen, `[]` valinnaisuuteen ja merkkiä `|` vaihtoehtoon.
Merkki `*` tarkoittaa "nolla tai useampi" ja `+` yksi tai useampi.

### cat

>`cat tiedosto*`

Lukee annetut tiedostot rivi kerrallaan ja työntää rivit ulostulovirtaan.

### false

>`false`

Työntää arvon `false` ulostulovirtaan.

### grep

>`grep [-o] regex`

Lukee sisääntulovirrasta merkkijonoarvoja. Jos merkkijono täsmää annettuun säännölliseen lausekkeeseen, se työnnetään
ulostulovirtaan. Jos valitsinta `-o` (**o**nly-matching) on käytetty, palautetaan merkkijonoista vain ne osat,
joihin säännöllinen lauseke täsmää.

### expr

>`expr merkkijono+`

Yhdistää merkkijonot ja antaa lopputuloksen laskimelle, joka palauttaa vastauksen ulostulovirtaan.
Tukee tällä hetkellä vain liukulukuja.

### exec

>`exec komento argumentit*`

Suorittaa annetun ulkoisen komennon annetuilla argumenteilla. Jos komennolle ei halua antaa syötettä tai sen
ulostuloa ei halua, voi sen putkittaa nimettömälle funktiolle:

`{} | exec komento argumentit | {}`

### head

>`head [määrä]`

Lukee yhden arvon (tai argumenttina annetun määrän arvoja) ja työntää sen/ne ulostulovirtaan.

### import

>`import tiedosto+`

Suorittaa annetut Röda-tiedostot.

### json

>`json [-i [-s]] [merkkijono]`

Parsii json-koodin, joka on joko annettu argumenttina tai kaikki sisääntulovirrasta annetut json-koodit (ei molempia).
Palauttaa koodien puut ulostulovirtaan tai, jos valitsin `-i` on annettu, avain-arvo-parit. Lisävalitsin `-s`
määrittää, että avaimet annetaan merkkijonoina listojen sijaan. (TODO parempi selitys)

### list

>`list arvo*`

Palauttaa argumentit listana.

### print

>`print arvo*`

Työntää annettut arvot ja rivinvaihdon ulostulovirtaan.

### pull

>`pull [-r] muuttuja+`

### push

>`push arvo+`

Työntää arvot ulostulovirtaan.

Lukee muuttujaan arvon sisääntulovirrasta. Jos valitsin `-r` on käytössä, palautetaan jokaista onnistunutta
lukua kohti ulostulovirtaan arvo `true` ja jokaista epäonnistunutta lukua kohti arvo `false`.

### random

>`random [-boolean|-float|-integer]`

Työntää oletuksena satunnaisen totuusarvon ulostulovirtaan.
Voi myös palauttaa kokonaisluvun tai liukuluvun merkkijonona.

### replace

>`replace (regex korvaava)+`

Lukee sisääntulovirrasta merkkijonoarvoja ja työntää ne ulostulovirtaan siten, että niihin on tehty annetut
korvaukset järjestyksessä. Käyttää sisäisesti Javan `String.replaceAll`-metodia.

### seq

>`seq alku loppu`

Palauttaa kokonaisluvut välillä `[alku, loppu]`.

### split

>`split [-s regex] merkkijono`

Palauttaa listan, jossa merkkijono on jaettu osiin annetun erottajan (-s, **s**eparator) osoittamista kohdista tai
oletuksena välilyöntien perusteella.

### test

>`test arvo [-not](-eq|-strong_eq|-weak_eq|-matches|-lt|-le|-gt|-ge) arvo`

Vertailee kahta arvoa annetulla operaattorilla. Valitsin `-not` tekee vertailusta käänteisen. Sen on oltava
kiinni isäntävalitsimessa (ei välilyöntiä).

- `-eq` on paras yksinkertainen ekvivalenssioperaattori. Se muuttaa luvut merkkijonoiksi ja toisin päin,
mutta vaatii muuten tyypeiltä yhtenevyyttä.
- `-strong_eq` vaatii, että kaikki tyypit yhtenevät, myös luvut ja merkkijonot.
- `-weak_eq` muuttaa operandit merkkijonoiksi ja vertailee niitä.
- `-matches` vertailee ensimmäistä operandia toiseen, jonka se olettaa olevan säännöllinen lauseke.

### tail

>`tail [määrä]`

Lukee kaikki arvot sisääntulovirrasta ja palauttaa viimeisen arvon (tai viimeiset argumenttina annettu määrä arvoa).

### time

>`time`

Palauttaa nykyisen ajan millisekuntteina.

### true

>`true`

Työntää arvon `true` ulostulovirtaan.

### undefine

>`undefine muuttuja+`

Tuhoaa annetut muuttujat.

### wcat

>`wcat ([-O tiedosto] [-U user_agent] osoite)*`

Lataa tiedostot annetuista Internet-osoitteista mahdollisesti annetuilla user agenteilla
ja kirjoittaa ne annettuihin tiedostoihin (tai oletuksena rivi kerrallaan ulostulovirtaan).

### write

>`write tiedoston_nimi`

Lukee kaiken sisääntulovirrasta, muuttaa arvot merkkijonoiksi ja kirjoittaa ne annettuun tiedostoon.