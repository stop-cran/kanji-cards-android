# Room database version registry

Reserve a new version here before changing the Room database version or exporting a schema, including on experimental
branches. A reservation may use `pending` as its identity until Room generates the schema; replace it with the complete
hash before committing an exported schema. Never reuse a number already listed, even for an abandoned branch. If a
historical collision is discovered, record every identity and reconcile it explicitly in the migration and tests.

The `RoomVersionRegistryTest` checks every checked-out exported schema's filename, embedded version and identity against
this table and requires every row marked `yes` in the Export column to exist in this checkout. Mark checked-out exports
`yes` and historical-only entries `no`. Only the recorded v22 and
v23 collisions are allowed; new collisions must be reconciled, not merely added to the registry. It does not prove a schema
is valid or a migration preserves data; migration tests remain necessary. Include this registry in a branch's changes
before its schema is generated, and reconcile concurrent reservations before merging.

Statuses: `released` means a build containing that identity was distributed; `reserved` means the number is unavailable
for reuse, including historical versions whose distribution is unverified. `abandoned` and `test-only` also remain reserved.
Historical rows below are not claims that every version was shipped.

| Version | Identity hash | Branch or source | Commit | Status | Export |
| --- | --- | --- | --- | --- | --- |
| 3 | 156514a557ccf17e05ae0904aea1f2bd | Main history | b41684e | reserved | yes |
| 4 | b36d85c874c39dc63ddecd3c2aa4251d | Main history | 59560d0 | reserved | yes |
| 5 | e7c9757638084ae652a3318ab382eaf4 | Main history | fc63c66 | reserved | yes |
| 6 | 460cba6550cce0ffb5825f037a71f353 | Main history | 1fe5e6c | reserved | yes |
| 7 | c72457d4b28b821be094de4ad8fd919d | Main history | fb4e646 | reserved | yes |
| 8 | 6ff53f24468674f1c4064e41a8f51671 | Main history | 30add6a | reserved | yes |
| 9 | 9c0458168e42182c0bed362d2b8b1605 | Main history | 2111004 | reserved | yes |
| 10 | 8c6a0e95a8c95e92bb137a1e9208b57e | Main history | f37e53f | reserved | yes |
| 11 | 09179b3d20b36f6b98a35a0c052334c1 | Main history | 9b7cc6d | reserved | yes |
| 12 | 09dc992adb08137fc3b590a3e44d5513 | Main history | 699921d | reserved | yes |
| 13 | be92ac705b3ac3844214e5a1aa848f96 | Main history | 4c28499 | reserved | yes |
| 14 | 59975d13ff01b12c81247167f4201163 | Main history | cf58640 | reserved | yes |
| 15 | 80badb285a6c2670c164e2385f91cf22 | Main history | bff1c82 | reserved | yes |
| 16 | dc670de91fd01dc2018feb473eb5ce73 | Main history | 6dfd023 | reserved | yes |
| 17 | 80df037c68b7911ce312bbdb70ea3fa7 | Main history | f9b59a2 | reserved | yes |
| 18 | 49be7c788534f65d6a607b8fb2a1b805 | Main history | 30b0719 | reserved | yes |
| 19 | 941c2651a9e34c589ff26ef1e19fde4e | Main history | 30b0719 | reserved | yes |
| 20 | 39f8edf5a5a58d6c0a4ee0a3e7d55f00 | Main history | 2edbf36 | reserved | yes |
| 21 | 693cd3ba3b8fa30b3ff905ca62f6caa9 | Main | 10d98d0 | released | yes |
| 22 | bb4bd6ed0ae5d551d26ef9d695be95b2 | Reading checkpoint | c50014a | reserved | yes |
| 22 | c15c3d949cdafd3afc602dec4a00e947 | Queued learning on 10d98d0 | uncommitted | released | no |
| 23 | a784ae19620761de81f6d70367395585 | Reading checkpoint, variant a | c5f29c7 | reserved | no |
| 23 | 51223af7be5dcde053f7428cc9952cc1 | Reading checkpoint, variant b | b52aad8 | released | no |
| 24 | ecadf2177c260909da03914c88b08759 | Reading checkpoint | 2132a02 | reserved | no |
| 25 | bb4bd6ed0ae5d551d26ef9d695be95b2 | Main, Reading schema retained | c4314c6 | released | yes |
