package io.geoverse.bench;
import static io.tileverse.parquetry.filter.Pred.col;
import io.tileverse.parquetry.data.ReadOptions;
import io.tileverse.parquetry.dataset.ParquetSource;
import io.tileverse.parquetry.filter.*;
import io.tileverse.parquetry.io.ByteRangeSource;
import io.tileverse.parquetry.record.ParquetRecord;
import io.tileverse.parquetry.schema.ColumnPath;
import io.tileverse.parquetry.tileverse.ByteRangeSources;
import io.tileverse.storage.*;
import io.tileverse.storage.s3.S3StorageProvider;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import java.io.IOException; import java.net.URI; import java.nio.ByteBuffer; import java.nio.file.*; import java.util.*; import java.util.concurrent.atomic.AtomicLong; import java.util.stream.Stream;

public final class ParquetryR2GeographicJumpBench {
 static final class CRR implements RangeReader {
  final RangeReader d; final AtomicLong calls=new AtomicLong(),req=new AtomicLong(),ret=new AtomicLong();
  CRR(RangeReader d){this.d=d;}
  public ByteBuffer readRange(long o,int l){calls.incrementAndGet();req.addAndGet(l);ByteBuffer b=d.readRange(o,l);ret.addAndGet(b.remaining());return b;}
  public int readRange(long o,int l,ByteBuffer t){calls.incrementAndGet();req.addAndGet(l);int n=d.readRange(o,l,t);if(n>0)ret.addAndGet(n);return n;}
  public OptionalLong size(){return d.size();} public String getSourceIdentifier(){return d.getSourceIdentifier();}
  public void close() throws IOException{d.close();} void reset(){calls.set(0);req.set(0);ret.set(0);}
 }
 public static void main(String[] a)throws Exception{
  if(a.length!=5)throw new IllegalArgumentException("BUCKET OBJECT WORKLOAD ENDPOINT REGION");
  List<String> lines=Files.readAllLines(Path.of(a[2]));
  try(S3AsyncClient c=S3AsyncClient.builder().endpointOverride(URI.create(a[3])).region(Region.of(a[4])).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(System.getenv("AWS_ACCESS_KEY_ID"),System.getenv("AWS_SECRET_ACCESS_KEY")))).forcePathStyle(true).build();
      Storage st=S3StorageProvider.open(URI.create("s3://"+a[0]+"/"),c);
      CRR rr=new CRR(st.openRangeReader(a[1])); ByteRangeSource src=ByteRangeSources.from(rr)){
   ParquetSource ds=ParquetSource.open(src);
   Projection pr=Projection.ofPhysical(List.of(ColumnPath.of("id"),ColumnPath.of("geometry"),ColumnPath.of("bbox")));
   System.out.println("position,query,rows,elapsed_ms,range_calls,range_bytes_requested,range_bytes_returned");
   for(int i=1;i<lines.size();i++){
    String[] x=lines.get(i).split(","); double minx=Double.parseDouble(x[1]),miny=Double.parseDouble(x[2]),maxx=Double.parseDouble(x[3]),maxy=Double.parseDouble(x[4]);
    Predicate pred=col("geometry").bboxIntersects(Bbox.of2d(minx,miny,maxx,maxy)); rr.reset(); long t=System.nanoTime(),rows;
    try(Stream<ParquetRecord>s=ds.read(pred,pr,ReadOptions.DEFAULTS)){rows=s.count();}
    double ms=(System.nanoTime()-t)/1e6;
    System.out.printf(Locale.ROOT,"%d,%s,%d,%.6f,%d,%d,%d%n",i,x[0],rows,ms,rr.calls.get(),rr.req.get(),rr.ret.get());
   }
  }
 }
}
