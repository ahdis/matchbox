rm -rf ./report-multi-ig
rm -f ./multi-ig.jtl
/Applications/apache-jmeter-5.6.2/bin/jmeter.sh -n -q ./user.properties -t ./multi-ig.jmx -l ./multi-ig.jtl -o ./report-multi-ig -e
